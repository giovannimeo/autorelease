package org.codehaus.mojo.versions;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.metadata.ArtifactMetadataRetrievalException;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.codehaus.mojo.versions.api.ArtifactVersions;
import org.codehaus.mojo.versions.api.PomHelper;
import org.codehaus.mojo.versions.rewriting.ModifiedPomXMLEventReader;

import javax.xml.stream.XMLStreamException;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Replaces any version with the latest version.
 *
 * @author Stephen Connolly
 * @goal rewritepom-to-latest-versions
 * @requiresProject true
 * @requiresDirectInvocation true
 * @since 1.0-alpha-3
 */
public class RewitePomToLatestVersionsMojo
    extends AbstractVersionsDependencyUpdaterMojo
{
    /**
     * Whether to allow the major version number to be changed.
     *
     * @parameter expression="${allowMajorUpdates}" default-value="true"
     * @since 1.2
     */
    protected Boolean allowMajorUpdates;

    /**
     * Whether to allow the minor version number to be changed.
     *
     * @parameter expression="${allowMinorUpdates}" default-value="true"
     * @since 1.2
     */
    protected Boolean allowMinorUpdates;

    /**
     * Whether to allow the incremental version number to be changed.
     *
     * @parameter expression="${allowIncrementalUpdates}" default-value="true"
     * @since 1.2
     */
    protected Boolean allowIncrementalUpdates;

    enum UpdateType {
        UpdateDependency,
        UpdatePlugin,
        UpdateParent,
    };

    // ------------------------------ METHODS --------------------------

    /**
     * @param pom the pom to update.
     * @throws org.apache.maven.plugin.MojoExecutionException
     *          when things go wrong
     * @throws org.apache.maven.plugin.MojoFailureException
     *          when things go wrong in a very bad way
     * @throws javax.xml.stream.XMLStreamException
     *          when things go wrong with XML streaming
     * @see AbstractVersionsUpdaterMojo#update(org.codehaus.mojo.versions.rewriting.ModifiedPomXMLEventReader)
     */
    @Override
    protected void update(ModifiedPomXMLEventReader pom) throws MojoExecutionException, MojoFailureException,
            XMLStreamException {
        try {
            if (getProject().getDependencyManagement() != null) {
                getLog().info("Search and rewrite updates in DependencyManagement");
                useLatestVersions(pom, getProject().getDependencyManagement()
                        .getDependencies());
            }
            getLog().info("Search and rewrite updates in Dependencies");
            useLatestVersions(pom, getProject().getDependencies());

            getLog().info("Search and rewrite updates in BuildPlugins");
            // Update also the plugins Artifacts
            useLatestVersionsFromPlugins(pom, getProject().getBuildPlugins());

            getLog().info("Search and rewrite updates in PluginManagement");
            // Update also the pluginManagement Artifacts
            useLatestVersionsFromPlugins(pom, getProject().getPluginManagement()
                    .getPlugins());

            if (getProject().getParentArtifact() != null) {
                int segment = determineUnchangedSegment(allowMajorUpdates, allowMinorUpdates, allowIncrementalUpdates);
                getLog().info("Search and rewrite updates in Parent POM");
                // Update also the Parent POM
                useLatestVersionsForArtifact(pom, getProject().getParentArtifact(), segment, UpdateType.UpdateParent);
            }

        } catch (ArtifactMetadataRetrievalException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private void useLatestVersions(ModifiedPomXMLEventReader pom, Collection<?> dependencies)
            throws XMLStreamException, MojoExecutionException, ArtifactMetadataRetrievalException {
        int segment = determineUnchangedSegment(allowMajorUpdates, allowMinorUpdates, allowIncrementalUpdates);
        Iterator<?> i = dependencies.iterator();

        while (i.hasNext()) {
            Dependency dep = (Dependency) i.next();

            if (isExcludeReactor() && isProducedByReactor(dep)) {
                getLog().debug("Ignoring reactor dependency: " + toString(dep));
                continue;
            }

            Artifact artifact = this.toArtifact(dep);
            useLatestVersionsForArtifact(pom, artifact, segment, UpdateType.UpdateDependency);
        }
    }

    private void useLatestVersionsForArtifact(ModifiedPomXMLEventReader pom, Artifact artifact, int segment,
            UpdateType type)
            throws XMLStreamException, MojoExecutionException, ArtifactMetadataRetrievalException {
        String version = artifact.getBaseVersion();
        if (!isIncluded(artifact)) {
            getLog().debug("Artifact:" + artifact + " is skipped");
            return;
        }

        getLog().debug("Looking for newer versions of " + artifact);
        ArtifactVersions versions = getHelper().lookupArtifactVersions(artifact, false);
        ArtifactVersion[] newer = versions.getNewerVersions(version, segment, Boolean.TRUE.equals(allowSnapshots));
        getLog().debug("Found #new versions:" + newer.length);
        if (newer.length > 0) {
            String newVersion = newer[newer.length - 1].toString();
            getLog().info("NewerVersion is:" + newVersion);
            switch (type) {
            case UpdateDependency:
                if (PomHelper.setDependencyVersion(pom, artifact.getGroupId(), artifact.getArtifactId(), version,
                        newVersion)) {
                    getLog().info("Updated DEPENDENCY for artifact:" + artifact + " to version " + newVersion);
                }
                break;
            case UpdatePlugin:
                if (PomHelper.setPluginVersion(pom, artifact.getGroupId(), artifact.getArtifactId(), version,
                        newVersion)) {
                    getLog().info("Updated PLUGIN for artifact:" + artifact + " to version " + newVersion);
                }
                break;
            case UpdateParent:
                if (PomHelper.setProjectParentVersion(pom, newVersion)) {
                    getLog().info("Updated PARENT artifact:" + artifact + " to version " + newVersion);
                }
                break;
            }
        }
    }

    private void useLatestVersionsFromPlugins(ModifiedPomXMLEventReader pom, Collection<?> plugins)
            throws XMLStreamException, MojoExecutionException, ArtifactMetadataRetrievalException {
        int segment = determineUnchangedSegment(allowMajorUpdates, allowMinorUpdates, allowIncrementalUpdates);
        Iterator<?> i = plugins.iterator();

        while (i.hasNext()) {
            Plugin plugin = (Plugin) i.next();

            if ((plugin.getGroupId() != null) && (plugin.getArtifactId()) != null && (plugin.getVersion() != null)) {
                Artifact pluginArtifact = null;
                try {
                    pluginArtifact =
                            new DefaultArtifact(plugin.getGroupId(), plugin.getArtifactId(),
                                    VersionRange.createFromVersionSpec(plugin.getVersion()), "", "", "", null);
                } catch (InvalidVersionSpecificationException e) {
                    pluginArtifact = null;
                }
                if (pluginArtifact != null) {
                    getLog().info("Try to update pluginArtifact " + pluginArtifact);
                    useLatestVersionsForArtifact(pom, pluginArtifact, segment, UpdateType.UpdatePlugin);
                }
            }

            List<Dependency> dependencies = plugin.getDependencies();
            if (dependencies != null) {
                Iterator<Dependency> j = dependencies.iterator();

                while (j.hasNext()) {
                    Dependency dep = (Dependency) j.next();

                    if (isExcludeReactor() && isProducedByReactor(dep)) {
                        getLog().debug("Ignoring reactor dependency: " + toString(dep));
                        continue;
                    }

                    Artifact artifact = this.toArtifact(dep);
                    useLatestVersionsForArtifact(pom, artifact, segment, UpdateType.UpdateDependency);
                }
            }
        }
    }
}