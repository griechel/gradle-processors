package org.inferred.gradle

import groovy.text.SimpleTemplateEngine
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectCollection
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ResolvedConfiguration
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.quality.FindBugs
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.Delete
import org.gradle.plugins.ide.eclipse.EclipsePlugin
import org.gradle.plugins.ide.idea.IdeaPlugin

class ProcessorsPlugin implements Plugin<Project> {

  void apply(Project project) {

    project.configurations.create('processor')

    /**** javac, groovy, etc. *********************************************************************/
    project.plugins.withType(JavaPlugin, { plugin ->
      project.sourceSets.each { it.compileClasspath += project.configurations.processor }
      project.compileJava.dependsOn project.task('processorPath', {
        doLast {
          String path = getProcessors(project).getAsPath()
          project.compileJava.options.compilerArgs += ["-processorpath", path]
        }
      })
      project.javadoc.dependsOn project.task('javadocProcessors', {
        doLast {
          Set<File> path = getProcessors(project).files
          project.javadoc.options.classpath += path
        }
      })
    })

    /**** Eclipse *********************************************************************************/
    project.plugins.withType(EclipsePlugin, { plugin ->
      project.plugins.withType(JavaBasePlugin, { javaBasePlugin ->
        project.plugins.withType(JavaPlugin, { javaPlugin ->
          project.eclipse {
            extensions.create('processors', EclipseProcessorsExtension)
            processors.conventionMapping.outputDir = {
              new File(project.eclipse.classpath.defaultOutputDir, 'generated/java')
            }

            classpath.plusConfigurations += [project.configurations.processor]
            if (jdt != null) {
              jdt.file.withProperties {
                it['org.eclipse.jdt.core.compiler.processAnnotations'] = 'enabled'
              }
            }
          }

          templateTask(
              project,
              'eclipseAptPrefs',
              'org/inferred/gradle/apt-prefs.template',
              '.settings/org.eclipse.jdt.apt.core.prefs',
              {[
                outputDir: project.relativePath(project.eclipse.processors.outputDir),
                deps: project.configurations.processor
              ]}
          )
          project.tasks.eclipseAptPrefs.inputs.file project.configurations.processor
          project.tasks.eclipse.dependsOn project.tasks.eclipseAptPrefs
          project.tasks.cleanEclipse.dependsOn project.tasks.cleanEclipseAptPrefs

          templateTask(
              project,
              'eclipseFactoryPath',
              'org/inferred/gradle/factorypath.template',
              '.factorypath',
              {[deps: project.configurations.processor]}
          )
          project.tasks.eclipseFactoryPath.inputs.file project.configurations.processor
          project.tasks.eclipse.dependsOn project.tasks.eclipseFactoryPath
          project.tasks.cleanEclipse.dependsOn project.tasks.cleanEclipseFactoryPath
        })
      })
    })

    /**** IntelliJ ********************************************************************************/
    project.plugins.withType(IdeaPlugin, { plugin ->
      project.plugins.withType(JavaPlugin, { javaPlugin ->
        project.idea.extensions.create('processors', IdeaProcessorsExtension)
        project.idea.processors {
          outputDir = 'generated_src'
          testOutputDir = 'generated_testSrc'
        }

        if (project.idea.module.scopes.PROVIDED != null) {
          project.idea.module.scopes.PROVIDED.plus += [project.configurations.processor]
        }

        addGeneratedSourceFolder(project, { project.idea.processors.outputDir }, false)
        addGeneratedSourceFolder(project, { project.idea.processors.testOutputDir }, true)

        // Root project configuration
        if (project.rootProject.idea.project != null) {
          project.rootProject.idea.project.ipr {
            withXml {
              updateIdeaCompilerConfiguration(project, node)
            }
          }
        }
      })
    })

    // If the project uses .idea directory structure, update compiler.xml directly
    File ideaCompilerXml = project.file('.idea/compiler.xml')
    if (ideaCompilerXml.isFile()) {
      Node parsedProjectXml = (new XmlParser()).parse(ideaCompilerXml)
      updateIdeaCompilerConfiguration(project, parsedProjectXml)
      ideaCompilerXml.withWriter { writer ->
        XmlNodePrinter nodePrinter = new XmlNodePrinter(new PrintWriter(writer))
        nodePrinter.setPreserveWhitespace(true)
        nodePrinter.print(parsedProjectXml)
      }
    }
  }

  /** Runs {@code action} on element {@code name} in {@code collection} whenever it is added. */
  private static <T> void withName(
      NamedDomainObjectCollection<T> collection, String name, Closure action) {
    T object = collection.findByName(name)
    if (object != null) {
      action.call(object)
    } else {
      collection.whenObjectAdded { o ->
        String oName = collection.getNamer().determineName(o)
        if (oName == name) {
          action.call(o)
        }
      }
    }
  }

  static FileCollection getProcessors(Project project) {
    ResolvedConfiguration config = project.configurations.processor.resolvedConfiguration
    return project.files(config.getFiles({ d -> true } as Spec<Object>))
  }

  static void templateTask(project, taskName, templateFilename, outputFilename, binding) {
    def outputFile = new File(project.projectDir, outputFilename)
    def cleanTaskName = "clean" + taskName.substring(0, 1).toUpperCase() + taskName.substring(1)
    project.task(taskName, {
      outputs.file outputFile
      doLast {
        outputFile.parentFile.mkdirs()
        def stream = getClass().classLoader.getResourceAsStream templateFilename
        try {
          def reader = new InputStreamReader(stream, "UTF-8")
          def template = new SimpleTemplateEngine().createTemplate(reader)
          def writable = template.make binding()
          def writer = new FileWriter(outputFile)
          try {
            writable.writeTo(writer)
          } finally {
            writer.close()
          }
        } finally {
          stream.close()
        }
      }
    })

    project.task(cleanTaskName, type: Delete) {
      delete outputFile
    }
  }

  static void updateIdeaCompilerConfiguration(Project project, Node projectConfiguration) {
    Object compilerConfiguration = projectConfiguration.component
            .find { it.@name == 'CompilerConfiguration' }

    if (compilerConfiguration == null) {
      throw new GradleException("Unable to find CompilerConfiguration element")
    }

    compilerConfiguration.annotationProcessing.replaceNode{
      annotationProcessing() {
        profile(default: 'true', name: 'Default', enabled: 'true') {
          sourceOutputDir(name: project.idea.processors.outputDir)
          sourceTestOutputDir(name: project.idea.processors.testOutputDir)
          outputRelativeToContentRoot(value: 'true')
          processorPath(useClasspath: 'true')
        }
      }
    }
  }

  private static void addGeneratedSourceFolder(
          Project project,
          Object outputDir,
          boolean isTest) {
    File generatedSourceOutputDir = project.file(outputDir)

    // add generated directory as source directory
    project.idea.module.generatedSourceDirs += project.file(outputDir)
    if (!isTest) {
      project.idea.module.sourceDirs += project.file(outputDir)
    } else {
      project.idea.module.testSourceDirs += project.file(outputDir)
    }

    // if generated source directory doesn't already exist, Gradle IDEA plugin will not add it as a source folder,
    // so manually add as generated source folder to the .iml
    project.idea.module.iml {
      withXml {
        def path = project.relativePath(outputDir)
        def dirUrl = "file://\$MODULE_DIR\$/${path}"
        def content = node.component.content[0]
        if (content.find { it.url == dirUrl } == null) {
          content.appendNode(
              'sourceFolder', [
                  url          : dirUrl,
                  isTestSource : isTest,
                  generated    : "true"
              ]
          )
        }
      }
    }
  }

}

class EclipseProcessorsExtension {
  Object outputDir
}

class IdeaProcessorsExtension {
  Object outputDir
  Object testOutputDir
}

