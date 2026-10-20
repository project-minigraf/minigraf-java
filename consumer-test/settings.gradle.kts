// Consumer test project: depends on minigraf-jvm only through its Maven
// coordinates, as a user's project does. See run.sh.
val minigrafRepo: String? = providers.gradleProperty("minigrafRepo").orNull

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (minigrafRepo != null) {
            // Local mode: minigraf-jvm comes only from the built artifacts,
            // never from Maven Central, even if that version is already there.
            exclusiveContent {
                forRepository { maven { url = uri(minigrafRepo) } }
                filter { includeGroup("io.github.project-minigraf") }
            }
        }
        mavenCentral()
    }
}

rootProject.name = "minigraf-jvm-consumer"
