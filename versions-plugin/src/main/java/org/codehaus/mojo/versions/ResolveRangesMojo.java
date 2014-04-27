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
import org.apache.maven.artifact.metadata.ArtifactMetadataRetrievalException;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.codehaus.mojo.versions.api.PomHelper;
import org.codehaus.mojo.versions.api.PropertyVersions;
import org.codehaus.mojo.versions.rewriting.ModifiedPomXMLEventReader;

import javax.xml.stream.XMLStreamException;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Attempts to resolve dependency version ranges to the specific version being used in the build. For example a version
 * range of "[1.0, 1.2)" would be resolved to the specific version currently in use "1.1".
 *
 * @author Paul Gier
 * @goal resolve-ranges
 * @requiresProject true
 * @requiresDirectInvocation true
 * @since 1.0-alpha-3
 */
public class ResolveRangesMojo
    extends AbstractVersionsDependencyUpdaterMojo
{
    /**
     * Whether to process the properties section of the project. If not
     * set will default to true.
     *
     * @parameter expression="${processProperties}" defaultValue="true"
     * @since 1.3
     */
    private Boolean processProperties;

    /**
     * A comma separated list of properties to update if they contain version-ranges.
     *
     * @parameter expression="${includeProperties}"
     * @since 1.3
     */
    private String includeProperties = null;

    /**
     * A comma separated list of properties to not update even if they contain version-ranges.
     *
     * @parameter expression="${excludeProperties}"
     * @since 1.3
     */
    private String excludeProperties = null;

    // ------------------------------ FIELDS ------------------------------

    /**
     * Pattern to match a version range. For example 1.0-20090128.202731-1
     */
    public final Pattern matchRangeRegex = Pattern.compile( "," );

    // ------------------------------ METHODS --------------------------

    /**
     * Should the project/properties section of the pom be processed.
     *
     * @return returns <code>true if the project/properties section of the pom should be processed.
     * @since 1.3
     */
    public boolean isProcessingProperties()
    {
        // true if true or null
        return !Boolean.FALSE.equals( processProperties );
    }

    /**
     * @param pom the pom to update.
     * @throws MojoExecutionException when things go wrong
     * @throws MojoFailureException   when things go wrong in a very bad way
     * @throws XMLStreamException     when things go wrong with XML streaming
     * @see AbstractVersionsUpdaterMojo#update(ModifiedPomXMLEventReader)
     */
    protected void update( ModifiedPomXMLEventReader pom )
        throws MojoExecutionException, MojoFailureException, XMLStreamException, ArtifactMetadataRetrievalException
    {
        // Note we have to get the dependencies from the model because the dependencies in the 
        // project may have already had their range resolved [MNG-4138]
        if ( getProject().getModel().getDependencyManagement() != null &&
            getProject().getModel().getDependencyManagement().getDependencies() != null &&
            isProcessingDependencyManagement() )
        {
            resolveRanges( pom, getProject().getModel().getDependencyManagement().getDependencies() );
        }
        if ( isProcessingDependencies() )
        {
            resolveRanges( pom, getProject().getModel().getDependencies() );
        }
        if ( isProcessingProperties() )
        {
            resolvePropertyRanges( pom );
        }
    }

    private void resolveRanges( ModifiedPomXMLEventReader pom, Collection<Dependency> dependencies )
        throws XMLStreamException, MojoExecutionException, ArtifactMetadataRetrievalException
    {

        for ( Dependency dep : dependencies )
        {
            if ( isExcludeReactor() && isProducedByReactor( dep ) )
            {
                continue;
            }

            Matcher versionMatcher = matchRangeRegex.matcher( dep.getVersion() );

            if ( versionMatcher.find() )
            {
                Artifact artifact = this.toArtifact( dep );

                if ( artifact != null && isIncluded( artifact ) )
                {
                    getLog().debug( "Resolving version range for dependency: " + artifact );

                    String artifactVersion = artifact.getVersion();
                    if ( artifactVersion == null )
                    {
                        ArtifactVersion latestVersion =
                            findLatestVersion( artifact, artifact.getVersionRange(), null, false );

                        if ( latestVersion != null )
                        {
                            artifactVersion = latestVersion.toString();
                        }
                        else
                        {
                            getLog().warn( "Not updating version " + artifact + " : could not resolve any versions" );
                        }
                    }

                    if ( artifactVersion != null )
                    {
                        if ( PomHelper.setDependencyVersion( pom, artifact.getGroupId(), artifact.getArtifactId(),
                                                             dep.getVersion(), artifactVersion ) )
                        {
                            getLog().debug( "Version set to " + artifactVersion + " for dependency: " + artifact );
                        }
                        else
                        {
                            getLog().warn( "Could not find the dependency " + artifact + " so unable to set version to "
                                               + artifactVersion );
                        }
                    }
                }
            }
        }
    }

    private void resolvePropertyRanges( ModifiedPomXMLEventReader pom )
        throws XMLStreamException, MojoExecutionException
    {

        Map<Property, PropertyVersions> propertyVersions =
            this.getHelper().getVersionPropertiesMap( getProject(), null, includeProperties, excludeProperties, true );
        for ( Map.Entry<Property, PropertyVersions> entry : propertyVersions.entrySet() )
        {
            Property property = entry.getKey();
            PropertyVersions version = entry.getValue();

            final String currentVersion = getProject().getProperties().getProperty( property.getName() );
            if ( currentVersion == null || !matchRangeRegex.matcher( currentVersion ).find() )
            {
                continue;
            }

            property.setVersion( currentVersion );
            updatePropertyToNewestVersion( pom, property, version, currentVersion );

        }
    }

}
