/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */

package org.openmrs.module.kenyacore.metadata;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.ModuleFactory;
import org.openmrs.module.kenyacore.ContentManager;
import org.openmrs.module.metadatasharing.ImportConfig;
import org.openmrs.module.metadatasharing.ImportMode;
import org.openmrs.module.metadatasharing.ImportedPackage;
import org.openmrs.module.metadatasharing.MetadataSharing;
import org.openmrs.module.metadatasharing.api.MetadataSharingService;
import org.openmrs.module.metadatasharing.wrapper.PackageImporter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Metadata package manager
 */
@Component
public class MetadataManager implements ContentManager {

	protected static final Log log = LogFactory.getLog(MetadataManager.class);

	/**
	 * @see org.openmrs.module.kenyacore.ContentManager#getPriority()
	 */
	@Override
	public int getPriority() {
		return 10; // First because others will use metadata loaded by it
	}

	/**
	 * @see org.openmrs.module.kenyacore.ContentManager#refresh()
	 */
	@Override
	public synchronized void refresh() {
		// Process configuration beans
		for (MetadataConfiguration configuration : Context.getRegisteredComponents(MetadataConfiguration.class)) {
			String moduleId = configuration.getModuleId();
			ClassLoader loader =  ModuleFactory.getModuleClassLoader(moduleId);

			try {
				loadPackages(configuration.getPackages(), loader);
			}
			catch (Exception ex) {
				throw new RuntimeException("Error occured while loading metadata packages from " + moduleId, ex);
			}
		}

		// Process installer components
		processInstallers(Context.getRegisteredComponents(AbstractMetadataInstaller.class));
	}

	/**
	 * Gets all imported packages in the system
	 * @return the packages
	 */
	public List<ImportedPackage> getImportedPackages() {
		return Context.getService(MetadataSharingService.class).getAllImportedPackages();
	}

	/**
	 * Loads packages specified in an XML packages list
	 * @param packages the map of groupUuids to package filenames
	 * @param loader the class loader to use for loading the packages (null to use the default)
	 * @return whether any changes were made to the db
	 * @throws Exception
	 */
	protected boolean loadPackages(Map<String, String> packages, ClassLoader loader) throws Exception {
		boolean anyChanges = false;

		for (Map.Entry<String, String> entry : packages.entrySet()) {
			String groupUuid = entry.getKey();
			String filename = entry.getValue();

			anyChanges |= ensurePackageInstalled(groupUuid, filename, loader);
		}

		return anyChanges;
	}

	/**
	 * Checks whether the given version of the MDS package has been installed yet, and if not, install it
	 * @param groupUuid the package group UUID
	 * @param filename the package filename
	 * @param loader the class loader to use for loading the packages (null to use the default)
	 * @return whether any changes were made to the db
	 * @throws IOException
	 */
	protected static boolean ensurePackageInstalled(String groupUuid, String filename, ClassLoader loader) throws IOException {
		try {
			Matcher matcher = Pattern.compile("[\\w/-]+-(\\d+).zip").matcher(filename);
			if (!matcher.matches())
				throw new RuntimeException("Filename must match PackageNameWithNoSpaces-X.zip");
			Integer version = Integer.valueOf(matcher.group(1));

			ImportedPackage installed = Context.getService(MetadataSharingService.class).getImportedPackageByGroup(groupUuid);
			if (installed != null && installed.getVersion() >= version) {
				log.info("Metadata package " + filename + " is already installed with version " + installed.getVersion());
				return false;
			}

			if (loader == null) {
				loader = MetadataManager.class.getClassLoader();
			}

			if (loader.getResource(filename) == null) {
				throw new RuntimeException("Cannot find " + filename + " for group " + groupUuid);
			}

			PackageImporter metadataImporter = MetadataSharing.getInstance().newPackageImporter();
			metadataImporter.setImportConfig(ImportConfig.valueOf(ImportMode.MIRROR));
			metadataImporter.loadSerializedPackageStream(loader.getResourceAsStream(filename));
			metadataImporter.importPackage();

			log.debug("Loaded metadata package '" + filename + "'");

			return true;

		} catch (Exception ex) {
			throw new RuntimeException("Failed to install metadata package " + filename, ex);
		}
	}

	/**
	 * Processes the given list of installers
	 * @param installers the installers
	 */
	protected void processInstallers(List<AbstractMetadataInstaller> installers) {
		// Organize into map by id
		Map<String, AbstractMetadataInstaller> all = new HashMap<String, AbstractMetadataInstaller>();
		for (AbstractMetadataInstaller installer : installers) {
			all.put(installer.getId(), installer);
		}

		// Begin recursive processing
		Set<AbstractMetadataInstaller> installed = new HashSet<AbstractMetadataInstaller>();
		for (AbstractMetadataInstaller installer : installers) {
			processInstaller(installer, all, installed);
		}
	}

	/**
	 * Processes an installer by recursively processing it's required installers
	 * @param installer the installer
	 * @param all the map of all installers and their ids
	 * @param installed the set of previously processed installers
	 */
	protected void processInstaller(AbstractMetadataInstaller installer, Map<String, AbstractMetadataInstaller> all, Set<AbstractMetadataInstaller> installed) {
		// Return immediately if installer has already been installed
		if (installed.contains(installer)) {
			return;
		}

		// Install required installers first
		Requires requires = installer.getClass().getAnnotation(Requires.class);
		if (requires != null) {
			for (String requiredId : requires.value()) {
				AbstractMetadataInstaller required = all.get(requiredId);

				if (required == null) {
					throw new RuntimeException("Can't find required installer " + requiredId + " for " + installer.getId());
				}

				processInstaller(required, all, installed);
			}
		}

		installer.install();
		installed.add(installer);

		Context.flushSession();
	}
}