package org.mybatis.generator.api;

import com.wuzhizhan.mybatis2.util.GeneratorCallback;
import org.mybatis.generator.api.intellij.IntellijTableInfo;
import org.mybatis.generator.codegen.RootClassInfo;
import org.mybatis.generator.config.Configuration;
import org.mybatis.generator.config.Context;
import org.mybatis.generator.config.MergeConstants;
import org.mybatis.generator.exception.InvalidConfigurationException;
import org.mybatis.generator.exception.ShellException;
import org.mybatis.generator.internal.DefaultShellCallback;
import org.mybatis.generator.internal.ObjectFactory;
import org.mybatis.generator.internal.XmlFileMergerJaxp;
import org.mybatis.generator.internal.util.ClassloaderUtility;
import org.mybatis.generator.internal.util.messages.Messages;

import java.io.*;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/* loaded from: mybatis-generator-core-1.4.0.jar:org/mybatis/generator/api/IntellijMyBatisGenerator.class */
public class IntellijMyBatisGenerator {
	private Configuration configuration;
	private ShellCallback shellCallback;
	private List<String> warnings;
	private List<GeneratedJavaFile> generatedJavaFiles = new ArrayList();
	private List<GeneratedXmlFile> generatedXmlFiles = new ArrayList();
	private List<GeneratedKotlinFile> generatedKotlinFiles = new ArrayList();
	private List<GeneratedFile> generatedFiles = new ArrayList();
	private Set<String> projects = new HashSet();

	public IntellijMyBatisGenerator(Configuration configuration, ShellCallback shellCallback, List<String> warnings) throws InvalidConfigurationException {
		if (configuration == null) {
			throw new IllegalArgumentException(Messages.getString("RuntimeError.2"));
		}
		this.configuration = configuration;
		if (shellCallback == null) {
			this.shellCallback = new DefaultShellCallback(false);
		} else {
			this.shellCallback = shellCallback;
		}
		if (warnings == null) {
			this.warnings = new ArrayList();
		} else {
			this.warnings = warnings;
		}
// 			this.configuration.validate();
	}

	public void generate(ProgressCallback callback, IntellijTableInfo tableInfo) throws SQLException, IOException, InterruptedException {
		generate(callback, null, null, true, tableInfo);
	}

	public void generate(ProgressCallback callback, Set<String> contextIds, IntellijTableInfo tableInfo) throws SQLException, IOException, InterruptedException {
		generate(callback, contextIds, null, true, tableInfo);
	}

	public void generate(ProgressCallback callback, Set<String> contextIds, Set<String> fullyQualifiedTableNames, IntellijTableInfo tableInfo) throws SQLException, IOException, InterruptedException {
		generate(callback, contextIds, fullyQualifiedTableNames, true, tableInfo);
	}

	public void generate(ProgressCallback callback, Set<String> contextIds, Set<String> fullyQualifiedTableNames, boolean writeFiles, IntellijTableInfo tableInfo) throws SQLException, IOException, InterruptedException {
		List<Context> contextsToRun;
		if (callback == null) {
			callback = new GeneratorCallback();
		}
		this.generatedJavaFiles.clear();
		this.generatedXmlFiles.clear();
		ObjectFactory.reset();
		RootClassInfo.reset();
		if (contextIds == null || contextIds.isEmpty()) {
			contextsToRun = this.configuration.getContexts();
		} else {
			contextsToRun = new ArrayList();
			for (Context context : this.configuration.getContexts()) {
				if (contextIds.contains(context.getId())) {
					contextsToRun.add(context);
				}
			}
		}
		if (!this.configuration.getClassPathEntries().isEmpty()) {
			ClassLoader classLoader = ClassloaderUtility.getCustomClassloader(this.configuration.getClassPathEntries());
			ObjectFactory.addExternalClassLoader(classLoader);
		}
		int totalSteps = 0;
		for (Context context2 : contextsToRun) {
			totalSteps += context2.getIntrospectionSteps();
		}
		callback.introspectionStarted(totalSteps);
		for (Context context3 : contextsToRun) {
			ContextIdeaAdder.introspectIntellijTables(context3, callback, this.warnings, fullyQualifiedTableNames, tableInfo);
		}
		int totalSteps2 = 0;
		for (Context context4 : contextsToRun) {
			totalSteps2 += context4.getGenerationSteps();
		}
		callback.generationStarted(totalSteps2);
		for (Context context5 : contextsToRun) {
			context5.generateFiles(callback, this.generatedJavaFiles, this.generatedXmlFiles, this.generatedKotlinFiles, this.generatedFiles, this.warnings);
		}
		if (writeFiles) {
			callback.saveStarted(this.generatedXmlFiles.size() + this.generatedJavaFiles.size());
			for (GeneratedXmlFile gxf : this.generatedXmlFiles) {
				this.projects.add(gxf.getTargetProject());
				writeGeneratedXmlFile(gxf, callback);
			}
			for (GeneratedJavaFile gjf : this.generatedJavaFiles) {
				this.projects.add(gjf.getTargetProject());
				writeGeneratedJavaFile(gjf, callback);
			}
			for (GeneratedKotlinFile gkf : this.generatedKotlinFiles) {
				this.projects.add(gkf.getTargetProject());
				writeGeneratedKotlinFile(gkf, callback);
			}
			for (String project : this.projects) {
				this.shellCallback.refreshProject(project);
			}
		}
		callback.done();
	}

	private void writeGeneratedJavaFile(GeneratedJavaFile gjf, ProgressCallback callback) throws InterruptedException, IOException {
		String source;
		try {
			File directory = this.shellCallback.getDirectory(gjf.getTargetProject(), gjf.getTargetPackage());
			File targetFile = new File(directory, gjf.getFileName());
			if (targetFile.exists()) {
				if (this.shellCallback.isMergeSupported()) {
					source = this.shellCallback.mergeJavaFile(gjf.getFormattedContent(), targetFile, MergeConstants.getOldElementTags(), gjf.getFileEncoding());
				} else if (this.shellCallback.isOverwriteEnabled()) {
					source = gjf.getFormattedContent();
					this.warnings.add(Messages.getString("Warning.11", targetFile.getAbsolutePath()));
				} else {
					source = gjf.getFormattedContent();
					targetFile = getUniqueFileName(directory, gjf.getFileName());
					this.warnings.add(Messages.getString("Warning.2", targetFile.getAbsolutePath()));
				}
			} else {
				source = gjf.getFormattedContent();
			}
			callback.checkCancel();
			callback.startTask(Messages.getString("Progress.15", targetFile.getName()));
			writeFile(targetFile, source, gjf.getFileEncoding());
		} catch (ShellException e) {
			this.warnings.add(e.getMessage());
		}
	}

	private void writeGeneratedKotlinFile(GeneratedKotlinFile gkf, ProgressCallback callback) throws InterruptedException, IOException {
		String source;
		try {
			File directory = this.shellCallback.getDirectory(gkf.getTargetProject(), gkf.getTargetPackage());
			File targetFile = new File(directory, gkf.getFileName());
			if (targetFile.exists()) {
				if (this.shellCallback.isOverwriteEnabled()) {
					source = gkf.getFormattedContent();
					this.warnings.add(Messages.getString("Warning.11", targetFile.getAbsolutePath()));
				} else {
					source = gkf.getFormattedContent();
					targetFile = getUniqueFileName(directory, gkf.getFileName());
					this.warnings.add(Messages.getString("Warning.2", targetFile.getAbsolutePath()));
				}
			} else {
				source = gkf.getFormattedContent();
			}
			callback.checkCancel();
			callback.startTask(Messages.getString("Progress.15", targetFile.getName()));
			writeFile(targetFile, source, gkf.getFileEncoding());
		} catch (ShellException e) {
			this.warnings.add(e.getMessage());
		}
	}

	private void writeGeneratedXmlFile(GeneratedXmlFile gxf, ProgressCallback callback) throws InterruptedException, IOException {
		String source;
		try {
			File directory = this.shellCallback.getDirectory(gxf.getTargetProject(), gxf.getTargetPackage());
			File targetFile = new File(directory, gxf.getFileName());
			if (targetFile.exists()) {
				if (gxf.isMergeable()) {
					source = XmlFileMergerJaxp.getMergedSource(gxf, targetFile);
				} else if (this.shellCallback.isOverwriteEnabled()) {
					source = gxf.getFormattedContent();
					this.warnings.add(Messages.getString("Warning.11", targetFile.getAbsolutePath()));
				} else {
					source = gxf.getFormattedContent();
					targetFile = getUniqueFileName(directory, gxf.getFileName());
					this.warnings.add(Messages.getString("Warning.2", targetFile.getAbsolutePath()));
				}
			} else {
				source = gxf.getFormattedContent();
			}
			callback.checkCancel();
			callback.startTask(Messages.getString("Progress.15", targetFile.getName()));
			writeFile(targetFile, source, "UTF-8");
		} catch (ShellException e) {
			this.warnings.add(e.getMessage());
		}
	}

	private void writeFile(File file, String content, String fileEncoding) throws IOException {
		OutputStreamWriter osw;
		FileOutputStream fos = new FileOutputStream(file, false);
		if (fileEncoding == null) {
			osw = new OutputStreamWriter(fos);
		} else {
			osw = new OutputStreamWriter(fos, fileEncoding);
		}
		BufferedWriter bw = new BufferedWriter(osw);
		Throwable th = null;
		try {
			try {
				bw.write(content);
				if (bw != null) {
					if (0 != 0) {
						try {
							bw.close();
							return;
						} catch (Throwable th2) {
							th.addSuppressed(th2);
							return;
						}
					}
					bw.close();
				}
			} catch (Throwable th3) {
				th = th3;
				throw th3;
			}
		} catch (Throwable th4) {
			if (bw != null) {
				if (th != null) {
					try {
						bw.close();
					} catch (Throwable th5) {
						th.addSuppressed(th5);
					}
				} else {
					bw.close();
				}
			}
			throw th4;
		}
	}

	private File getUniqueFileName(File directory, String fileName) {
		File answer = null;
		StringBuilder sb = new StringBuilder();
		int i = 1;
		while (true) {
			if (i >= 1000) {
				break;
			}
			sb.setLength(0);
			sb.append(fileName);
			sb.append('.');
			sb.append(i);
			File testFile = new File(directory, sb.toString());
			if (testFile.exists()) {
				i++;
			} else {
				answer = testFile;
				break;
			}
		}
		if (answer == null) {
			throw new RuntimeException(Messages.getString("RuntimeError.3", directory.getAbsolutePath()));
		}
		return answer;
	}

	public List<GeneratedJavaFile> getGeneratedJavaFiles() {
		return this.generatedJavaFiles;
	}

	public List<GeneratedKotlinFile> getGeneratedKotlinFiles() {
		return this.generatedKotlinFiles;
	}

	public List<GeneratedXmlFile> getGeneratedXmlFiles() {
		return this.generatedXmlFiles;
	}
}
