/*
 * Copyright 2020 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.maven.extension.layerfilter;

import com.google.cloud.tools.jib.api.buildplan.ContainerBuildPlan;
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer;
import com.google.cloud.tools.jib.api.buildplan.FileEntry;
import com.google.cloud.tools.jib.api.buildplan.LayerObject;
import com.google.cloud.tools.jib.maven.extension.JibMavenPluginExtension;
import com.google.cloud.tools.jib.maven.extension.MavenData;
import com.google.cloud.tools.jib.plugins.extension.ExtensionLogger;
import com.google.cloud.tools.jib.plugins.extension.ExtensionLogger.LogLevel;
import com.google.cloud.tools.jib.plugins.extension.JibPluginExtensionException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Verify;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.util.filter.ScopeDependencyFilter;

@Named
@Singleton
public class JibLayerFilterExtension implements JibMavenPluginExtension<Configuration> {

  private Map<PathMatcher, String> pathMatchers = new LinkedHashMap<>();

  @VisibleForTesting @Inject ProjectDependenciesResolver dependencyResolver;

  // (layer name, layer builder) map for new layers of configured <toLayer>
  @VisibleForTesting Map<String, FileEntriesLayer.Builder> newToLayers = new LinkedHashMap<>();

  @Override
  public Optional<Class<Configuration>> getExtraConfigType() {
    return Optional.of(Configuration.class);
  }

  @Override
  public ContainerBuildPlan extendContainerBuildPlan(
      ContainerBuildPlan buildPlan,
      Map<String, String> properties,
      Optional<Configuration> config,
      MavenData mavenData,
      ExtensionLogger logger)
      throws JibPluginExtensionException {
    logger.log(LogLevel.LIFECYCLE, "Running Jib Layer Filter Extension");
    if (!config.isPresent()) {
      logger.log(LogLevel.WARN, "Nothing configured for Jib Layer Filter Extension");
      return buildPlan;
    }

    preparePathMatchersAndLayerBuilders(buildPlan, config.get());

    ContainerBuildPlan.Builder newPlanBuilder = buildPlan.toBuilder();
    newPlanBuilder.setLayers(Collections.emptyList());

    @SuppressWarnings("unchecked")
    List<FileEntriesLayer> originalLayers = (List<FileEntriesLayer>) buildPlan.getLayers();
    // Start filtering original layers.
    for (FileEntriesLayer layer : originalLayers) {
      List<FileEntry> filesToKeep = new ArrayList<>();

      for (FileEntry entry : layer.getEntries()) {
        Optional<String> finalLayerName = determineFinalLayerName(entry, layer.getName());
        // Either keep, move, or delete this FileEntry.
        if (finalLayerName.isPresent()) {
          if (finalLayerName.get().equals(layer.getName())) {
            filesToKeep.add(entry);
          } else {
            FileEntriesLayer.Builder targetLayerBuilder =
                Verify.verifyNotNull(newToLayers.get(finalLayerName.get()));
            targetLayerBuilder.addEntry(entry);
          }
        }
      }

      if (!filesToKeep.isEmpty()) {
        newPlanBuilder.addLayer(layer.toBuilder().setEntries(filesToKeep).build());
      }
    }

    // Add newly created non-empty to-layers (if any).
    newToLayers
        .values()
        .stream()
        .map(FileEntriesLayer.Builder::build)
        .filter(layer -> !layer.getEntries().isEmpty())
        .forEach(newPlanBuilder::addLayer);

    ContainerBuildPlan newPlan = newPlanBuilder.build();

    return config.get().isCreateParentDependencyLayers()
        ? moveParentDepsToNewLayers(newPlan, mavenData, logger)
        : newPlan;
  }

  private ContainerBuildPlan moveParentDepsToNewLayers(
      ContainerBuildPlan buildPlan, MavenData mavenData, ExtensionLogger logger)
      throws JibPluginExtensionException {

    logger.log(LogLevel.INFO, "Moving parent dependencies to new layers.");

    // the key is the expected path for the parent dependency
    Map<String, Artifact> parentDependencies =
        getParentDependencies(mavenData)
            .stream()
            .map(Dependency::getArtifact)
            // TODO: configurable?
            .collect(
                Collectors.toMap(
                    artifact ->
                        "/app/libs/"
                            + artifact.getArtifactId()
                            + "-"
                            + artifact.getBaseVersion()
                            + ".jar",
                    artifact -> artifact));

    // parent dependencies that have not been found in any layer (due to different version or
    // filtering)
    Map<String, Artifact> parentDependenciesNotFound = new HashMap<>(parentDependencies);

    List<FileEntriesLayer.Builder> newLayerBuilders = new ArrayList<>();

    @SuppressWarnings("unchecked")
    List<FileEntriesLayer> originalLayers = ((List<FileEntriesLayer>) buildPlan.getLayers());
    originalLayers.forEach(
        originalLayer -> {
          // for each layer, create a parent layer
          String parentLayerName = originalLayer.getName() + "-parent";
          FileEntriesLayer.Builder parentLayerBuilder =
              FileEntriesLayer.builder().setName(parentLayerName);
          newLayerBuilders.add(parentLayerBuilder);
          // ... and the normal layer
          FileEntriesLayer.Builder layerBuilder =
              FileEntriesLayer.builder().setName(originalLayer.getName());
          newLayerBuilders.add(layerBuilder);

          originalLayer
              .getEntries()
              .forEach(
                  entry -> {
                    String path = entry.getExtractionPath().toString();
                    if (parentDependencies.containsKey(path)) {
                      // move to parent layer
                      logger.log(LogLevel.DEBUG, "Moving " + path + " to " + parentLayerName + ".");
                      parentLayerBuilder.addEntry(entry);
                      // mark parent dep as found
                      parentDependenciesNotFound.remove(path);
                    } else {
                      // keep in original layer
                      logger.log(
                          LogLevel.DEBUG, "Keep " + path + " in " + originalLayer.getName() + ".");
                      layerBuilder.addEntry(entry);
                    }
                  });
        });

    parentDependenciesNotFound.forEach(
        (path, artifact) -> {
          logger.log(LogLevel.INFO, "Dependency from parent not found: " + path);
        });

    List<FileEntriesLayer> newLayers =
        newLayerBuilders
            .stream()
            .map(FileEntriesLayer.Builder::build)
            .filter(layer -> !layer.getEntries().isEmpty())
            .collect(Collectors.toList());
    return buildPlan.toBuilder().setLayers(newLayers).build();
  }

  private List<Dependency> getParentDependencies(MavenData mavenData)
      throws JibPluginExtensionException {
    if (mavenData.getMavenProject().getParent() == null) {
      throw new JibPluginExtensionException(
          getClass(), "Try to get parent dependencies, but project has no parent.");
    }
    if (dependencyResolver == null) {
      throw new JibPluginExtensionException(
          getClass(),
          "Try to get parent dependencies, but ProjectDependenciesResolver is null. Please use a more recent jib plugin version to fix this.");
    }
    try {

      DefaultDependencyResolutionRequest request =
          new DefaultDependencyResolutionRequest(
              mavenData.getMavenProject().getParent(),
              mavenData.getMavenSession().getRepositorySession());
      request.setResolutionFilter(new ScopeDependencyFilter("test"));
      DependencyResolutionResult resolutionResult = dependencyResolver.resolve(request);

      return resolutionResult.getDependencies();
    } catch (DependencyResolutionException ex) {
      throw new JibPluginExtensionException(
          getClass(), "Error when getting parent dependencies: ", ex);
    }
  }

  private void preparePathMatchersAndLayerBuilders(
      ContainerBuildPlan buildPlan, Configuration config) throws JibPluginExtensionException {
    List<String> originalLayerNames =
        buildPlan.getLayers().stream().map(LayerObject::getName).collect(Collectors.toList());

    for (Configuration.Filter filter : config.getFilters()) {
      String toLayerName = filter.getToLayer();
      if (!toLayerName.isEmpty() && originalLayerNames.contains(toLayerName)) {
        throw new JibPluginExtensionException(
            getClass(),
            "moving files into built-in layer '"
                + toLayerName
                + "' is not supported; specify a new layer name in '<toLayer>'.");
      }
      if (filter.getGlob().isEmpty()) {
        throw new JibPluginExtensionException(
            getClass(), "glob pattern not given in filter configuration");
      }

      pathMatchers.put(
          FileSystems.getDefault().getPathMatcher("glob:" + filter.getGlob()), filter.getToLayer());

      if (!newToLayers.containsKey(toLayerName)) {
        newToLayers.put(toLayerName, FileEntriesLayer.builder().setName(toLayerName));
      }
    }
  }

  /**
   * Determines where this {@code fileEntry} finally belongs after filtering. The last matching
   * filter in the configuration order wins.
   *
   * @param fileEntry file entry in question
   * @param originalLayerName name of the original layer where {@code fileEntry} exists
   * @return final layer name into which {@code fileEntry} should move. May be same as {@code
   *     originalLayerName}. {@link Optional#empty()} indicates deletion.
   */
  private Optional<String> determineFinalLayerName(FileEntry fileEntry, String originalLayerName) {
    Optional<String> finalLayerName = Optional.of(originalLayerName);

    for (Map.Entry<PathMatcher, String> mapEntry : pathMatchers.entrySet()) {
      PathMatcher matcher = mapEntry.getKey();
      Path pathInContainer = Paths.get(fileEntry.getExtractionPath().toString());
      if (matcher.matches(pathInContainer)) {
        String toLayerName = mapEntry.getValue();
        if (toLayerName.isEmpty()) {
          finalLayerName = Optional.empty(); // Mark deletion.
        } else {
          finalLayerName = Optional.of(toLayerName);
        }
      }
    }
    return finalLayerName;
  }
}
