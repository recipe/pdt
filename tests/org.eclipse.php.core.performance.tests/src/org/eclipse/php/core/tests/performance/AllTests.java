/*******************************************************************************
 * Copyright (c) 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Zend Technologies
 *******************************************************************************/
package org.eclipse.php.core.tests.performance;

import junit.framework.Test;
import junit.framework.TestSuite;

import org.eclipse.dltk.core.IScriptProject;
import org.eclipse.dltk.core.tests.model.AbstractModelTests;

public class AllTests {

	public static final String PROJECT = "ZendFramework";

	public static Test suite() {
		TestSuite suite = new TestSuite(
				"Performance Test for org.eclipse.php.core");

		// $JUnit-BEGIN$
		suite.addTestSuite(SetupEnvironment.class);

		suite.addTestSuite(BuildProjectTest.class);
		suite.addTestSuite(PHPModelAccessTest.class);
		suite.addTestSuite(TypeHierarchyTest.class);
		suite.addTestSuite(CodeAssistAccessTest.class);

		suite.addTestSuite(CleanupEnvironment.class);
		// $JUnit-END$

		return suite;
	}

	/**
	 * Dummy test for setting up environment
	 */
	public static class SetupEnvironment extends AbstractModelTests {

		public SetupEnvironment(String name) {
			super(PHPCorePerformanceTests.PLUGIN_ID, name);
		}

		public void testSetup() throws Exception {
			deleteProject(PROJECT);
			IScriptProject scriptProject = setUpScriptProject(PROJECT);

			Util
					.downloadAndExtract(
							"http://framework.zend.com/releases/ZendFramework-1.9.5/ZendFramework-1.9.5.zip",
							scriptProject.getProject().getLocation().toString());
		}
	}

	/**
	 * Dummy test for cleaning up environment
	 */
	public static class CleanupEnvironment extends AbstractModelTests {

		public CleanupEnvironment(String name) {
			super(PHPCorePerformanceTests.PLUGIN_ID, name);
		}

		public void testCleanup() throws Exception {
			deleteProject(PROJECT);
		}
	}
}