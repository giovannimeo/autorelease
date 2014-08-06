package org.codehaus.mojo.wagon.shared;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.artifact.manager.WagonConfigurationException;
import org.apache.maven.artifact.manager.WagonManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.wagon.UnsupportedProtocolException;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.WagonException;
import org.apache.maven.wagon.observers.Debug;
import org.apache.maven.wagon.proxy.ProxyInfo;
import org.apache.maven.wagon.repository.Repository;
import org.apache.maven.wagon.repository.RepositoryPermissions;
import org.codehaus.plexus.util.StringUtils;

public class WagonUtils
{
    /**
     * Convenient method to create a wagon
     * 
     * @param id
     * @param url
     * @param wagonManager
     * @param settings
     * @param logger
     * @return
     * @throws MojoExecutionException
     */
    public static Wagon createWagon( String id, String url, WagonManager wagonManager, Settings settings, Log logger )
        throws WagonException, UnsupportedProtocolException, WagonConfigurationException
    {
        Wagon wagon = null;

        final Repository repository = new Repository( id, url );
        repository.setPermissions( getPermissions( id, settings ) );

        wagon = wagonManager.getWagon( repository );

        if ( logger.isDebugEnabled() )
        {
            Debug debug = new Debug();
            wagon.addSessionListener( debug );
            wagon.addTransferListener( debug );
        }

        ProxyInfo proxyInfo = getProxyInfo( settings );
        if ( proxyInfo != null )
        {
            wagon.connect( repository, wagonManager.getAuthenticationInfo( repository.getId() ), proxyInfo );
        }
        else
        {
            wagon.connect( repository, wagonManager.getAuthenticationInfo( repository.getId() ) );
        }

        return wagon;
    }

    public static WagonFileSet getWagonFileSet( String fromDir, String includes, String excludes,
                                                boolean isCaseSensitive, String toDir )
    {
        WagonFileSet fileSet = new WagonFileSet();
        fileSet.setDirectory( fromDir );

        if ( !StringUtils.isBlank( includes ) )
        {
            fileSet.setIncludes( StringUtils.split( includes, "," ) );
        }

        if ( !StringUtils.isBlank( excludes ) )
        {
            fileSet.setExcludes( StringUtils.split( excludes, "," ) );
        }

        fileSet.setCaseSensitive( isCaseSensitive );

        fileSet.setOutputDirectory( toDir );

        return fileSet;

    }

    /**
     * Convenience method to map a <code>Proxy</code> object from the user system settings to a <code>ProxyInfo</code>
     * object.
     * 
     * @return a proxyInfo object or null if no active proxy is define in the settings.xml
     */
    public static ProxyInfo getProxyInfo( Settings settings )
    {
        ProxyInfo proxyInfo = null;
        if ( settings != null && settings.getActiveProxy() != null )
        {
            Proxy settingsProxy = settings.getActiveProxy();

            proxyInfo = new ProxyInfo();
            proxyInfo.setHost( settingsProxy.getHost() );
            proxyInfo.setType( settingsProxy.getProtocol() );
            proxyInfo.setPort( settingsProxy.getPort() );
            proxyInfo.setNonProxyHosts( settingsProxy.getNonProxyHosts() );
            proxyInfo.setUserName( settingsProxy.getUsername() );
            proxyInfo.setPassword( settingsProxy.getPassword() );
        }

        return proxyInfo;
    }

    private static RepositoryPermissions getPermissions( String id, Settings settings )
    {
        // May not have an id
        if ( StringUtils.isBlank( id ) )
        {
            return null;
        }

        // May not be a server matching that id
        Server server = settings.getServer( id );
        if ( server == null )
        {
            return null;
        }

        // Extract perms (if there are any)
        String filePerms = server.getFilePermissions();
        String dirPerms = server.getDirectoryPermissions();

        // Check to see if custom permissions were supplied
        if ( StringUtils.isBlank( filePerms ) && StringUtils.isBlank( dirPerms ) )
        {
            return null;
        }

        // There are custom permissions specified in settings.xml for this server
        RepositoryPermissions permissions = new RepositoryPermissions();
        permissions.setFileMode( filePerms );
        permissions.setDirectoryMode( dirPerms );
        return permissions;
    }

}
