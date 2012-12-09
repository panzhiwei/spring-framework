import org.gradle.api.*
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.maven.Conf2ScopeMapping
import org.gradle.api.plugins.MavenPlugin
import org.gradle.api.tasks.*
import org.gradle.plugins.ide.eclipse.EclipsePlugin
import org.gradle.plugins.ide.eclipse.model.EclipseClasspath;
import org.gradle.plugins.ide.idea.IdeaPlugin
import org.gradle.api.invocation.*

/**
 * Gradle plugin that allows projects to merged together. Primarily developed to
 * allow Spring to support multiple multiple incompatible versions of third-party
 * dependencies (for example Hibernate v3 and v4).
 * <p>
 * The 'merge' extension should be used to define how projects are merged, for example:
 * <pre>
 * configure(subprojects) {
 *     apply plugin: MergePlugin
 * }
 *
 * project("myproject") {
 * }
 *
 * project("myproject-extra") {
 *     merge.into = project("myproject")
 * }
 * </pre>
 * <p>
 * This plugin adds two new configurations:
 * <ul>
 * <li>merging - Contains the projects being merged into this project<li>
 * <li>runtimeMerge - Contains all dependencies that are merge projects. These are used
 * to allow an IDE to reference merge projects.</li>
 * <ul>
 *
 * @author Rob Winch
 * @author Phillip Webb
 */
class MergePlugin implements Plugin<Project> {

	private static boolean attachedProjectsEvaluated;

	public void apply(Project project) {
		project.plugins.apply(MavenPlugin)
		project.plugins.apply(EclipsePlugin)
		project.plugins.apply(IdeaPlugin)

		MergeModel model = project.extensions.create("merge", MergeModel)
		project.configurations.add("merging")
		Configuration runtimeMerge = project.configurations.add("runtimeMerge")

		// Ensure the IDE can reference merged projects
		project.eclipse.classpath.plusConfigurations += [runtimeMerge]
		project.idea.module.scopes.PROVIDED.plus += runtimeMerge

		// Hook to perform the actual merge logic
		project.afterEvaluate{
			if(it.merge.into != null) {
				setup(it)
			}
		}

		// Hook to build runtimeMerge dependencies
		if(!attachedProjectsEvaluated) {
			project.gradle.projectsEvaluated{
				postProcessProjects(it)
			}
			attachedProjectsEvaluated  = true;
		}
	}

	private void setup(Project project) {
		project.merge.into.dependencies.add("merging", project)
		project.dependencies.add("provided", project.merge.into.sourceSets.main.output)
		project.dependencies.add("runtimeMerge", project.merge.into)
		setupTaskDependencies(project)
		setupMaven(project)
	}

	private void setupTaskDependencies(Project project) {
		// invoking a task will invoke the task with the same name on 'into' project
		["sourcesJar", "jar", "javadocJar", "javadoc", "install", "artifactoryPublish"].each {
			def task = project.tasks.findByPath(it)
			if(task) {
				task.enabled = false
				task.dependsOn(project.merge.into.tasks.findByPath(it))
			}
		}

		// update 'into' project artifacts to contain the source artifact contents
		project.merge.into.sourcesJar.from(project.sourcesJar.source)
		project.merge.into.jar.from(project.jar.source)
		project.merge.into.javadoc {
			source += project.javadoc.source
			classpath += project.javadoc.classpath
		}
	}

	private void setupMaven(Project project) {
		project.configurations.each { configuration ->
			Conf2ScopeMapping mapping = project.conf2ScopeMappings.getMapping([configuration])
			if(mapping.scope) {
				Configuration intoConfiguration = project.merge.into.configurations.add(
					project.name + "-" + configuration.name)
				intoConfiguration.dependencies.addAll(configuration.dependencies)
				project.merge.into.install.repositories.mavenInstaller.pom.scopeMappings.addMapping(
					mapping.priority + 100, intoConfiguration, mapping.scope)
			}
		}
	}

	private postProcessProjects(Gradle gradle) {
		gradle.allprojects(new Action<Project>() {
			public void execute(Project project) {
				project.configurations.getByName("runtime").allDependencies.withType(ProjectDependency).each{
					Configuration dependsOnMergedFrom = it.dependencyProject.configurations.getByName("merging");
					dependsOnMergedFrom.dependencies.each{ dep ->
						project.dependencies.add("runtimeMerge", dep.dependencyProject)
					}
				}
			}
		});
	}
}

class MergeModel {
	Project into;
}