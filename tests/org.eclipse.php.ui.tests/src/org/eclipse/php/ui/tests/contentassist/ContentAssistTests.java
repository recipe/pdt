/*******************************************************************************
 * Copyright (c) 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     William Candillon - initial API and implementation
 *******************************************************************************/
package org.eclipse.php.ui.tests.contentassist;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import junit.extensions.TestSetup;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.php.core.tests.AbstractPDTTTest;
import org.eclipse.php.core.tests.PHPCoreTests;
import org.eclipse.php.core.tests.PdttFile;
import org.eclipse.php.internal.core.PHPCoreConstants;
import org.eclipse.php.internal.core.PHPVersion;
import org.eclipse.php.internal.core.project.PHPNature;
import org.eclipse.php.internal.ui.PHPUiPlugin;
import org.eclipse.php.internal.ui.editor.PHPStructuredEditor;
import org.eclipse.php.ui.tests.PHPUiTests;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.wst.sse.ui.internal.StructuredTextViewer;

@SuppressWarnings("restriction")
public class ContentAssistTests extends AbstractPDTTTest {

	protected static IProject project;
	protected static IFile testFile;
	protected static PHPVersion phpVersion;
	protected static PHPStructuredEditor fEditor;
	protected static final Map<PHPVersion, String> TESTS = new LinkedHashMap<PHPVersion, String>();
	static {
		// TESTS.put(PHPVersion.PHP4, "/workspace/codeassist/php4");
		TESTS.put(PHPVersion.PHP5, "/workspace/codeassist/php5");
		TESTS.put(PHPVersion.PHP5_3, "/workspace/codeassist/php53");
	};
	protected static final char OFFSET_CHAR = '|';

	public static void setUpSuite() throws Exception {
		project = ResourcesPlugin.getWorkspace().getRoot().getProject(
				"Content Assist");
		if (project.exists()) {
			return;
		}

		project.create(null);
		project.open(null);

		// configure nature
		IProjectDescription desc = project.getDescription();
		desc.setNatureIds(new String[] { PHPNature.ID });
		project.setDescription(desc, null);

		// set auto insert to true,if there are only one proposal in the CA,it
		// will insert the proposal,so we can test CA without UI interaction
		PHPUiPlugin.getDefault().getPluginPreferences().setDefault(
				PHPCoreConstants.CODEASSIST_AUTOINSERT, true);
	}

	public static void tearDownSuite() throws Exception {
		project.close(null);
		project.delete(true, true, null);
		project = null;
	}

	public ContentAssistTests(String description) {
		super(description);
	}

	public static Test suite() {

		TestSuite suite = new TestSuite("Content Assist Tests");
		for (Entry<PHPVersion, String> pair : TESTS.entrySet()) {
			phpVersion = pair.getKey();
			TestSuite phpVerSuite = new TestSuite(phpVersion.getAlias());

			String[] files = getPDTTFiles(pair.getValue(), PHPUiTests
					.getDefault().getBundle());

			for (final String fileName : files) {
				try {
					final PdttFile pdttFile = new PdttFile(PHPUiTests
							.getDefault().getBundle(), fileName);
					phpVerSuite.addTest(new ContentAssistTests(phpVersion
							.getAlias()
							+ " - /" + fileName) {

						protected void setUp() throws Exception {
							PHPCoreTests.setProjectPhpVersion(project,
									PHPVersion.PHP5_3);
						}

						protected void tearDown() throws Exception {
							if (testFile != null) {
								testFile.delete(true, null);
								testFile = null;
							}
						}

						protected void runTest() throws Throwable {
							String data = pdttFile.getFile();
							int offset = data.lastIndexOf(OFFSET_CHAR);

							// replace the offset character
							data = data.substring(0, offset)
									+ data.substring(offset + 1);

							createFile(new ByteArrayInputStream(data.getBytes()));
							String result = executeAutoInsert(offset);
							closeEditor();
							if (!pdttFile.getExpected().trim().equals(
									result.trim())) {
								StringBuilder errorBuf = new StringBuilder();
								errorBuf.append("\nEXPECTED COMPLETIONS LIST:\n-----------------------------\n");
								errorBuf.append(pdttFile.getExpected());
								errorBuf.append("\nACTUAL COMPLETIONS LIST:\n-----------------------------\n");
								errorBuf.append(result);
								fail(errorBuf.toString());
							}
						}
					});
				} catch (final Exception e) {
					phpVerSuite.addTest(new TestCase(fileName) {
						protected void runTest() throws Throwable {
							throw e;
						}
					});
				}
			}
			suite.addTest(phpVerSuite);
		}

		// Create a setup wrapper
		TestSetup setup = new TestSetup(suite) {
			protected void setUp() throws Exception {
				setUpSuite();
			}

			protected void tearDown() throws Exception {
				tearDownSuite();
			}
		};
		return setup;
	}

	protected static void closeEditor() {
		fEditor.close(false);
		fEditor = null;
	}

	protected static String executeAutoInsert(int offset) {
		StructuredTextViewer viewer = fEditor.getTextViewer();
		viewer.getTextWidget().setCaretOffset(offset);
		viewer.doOperation(ISourceViewer.CONTENTASSIST_PROPOSALS);
		return fEditor.getDocument().get();
	}

	protected static void createFile(InputStream inputStream) throws Exception {
		testFile = project.getFile("test.php");
		testFile.create(inputStream, true, null);
		project.refreshLocal(IResource.DEPTH_INFINITE, null);

		project.build(IncrementalProjectBuilder.FULL_BUILD, null);
		PHPCoreTests.waitForIndexer();
		IWorkbenchWindow workbenchWindow = PlatformUI.getWorkbench()
				.getActiveWorkbenchWindow();
		IWorkbenchPage page = workbenchWindow.getActivePage();
		IEditorInput input = new FileEditorInput(testFile);
		/*
		 * This should take care of testing init, createPartControl,
		 * beginBackgroundOperation, endBackgroundOperation methods
		 */
		IEditorPart part = page.openEditor(input, "org.eclipse.php.editor",
				true);
		if (part instanceof PHPStructuredEditor)
			fEditor = (PHPStructuredEditor) part;
		else
			assertTrue("Unable to open php editor", false);
		// PHPCoreTests.waitForAutoBuild();
	}
}