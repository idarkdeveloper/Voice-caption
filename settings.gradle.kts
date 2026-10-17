pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx is not on Maven Central or JitPack (JitPack builds publish no modules); the
        // official Android distribution is the AAR attached to each GitHub release. Resolve it as
        // an Ivy artifact, restricted to that one module so no other lookup goes to GitHub.
        exclusiveContent {
            forRepository {
                ivy {
                    url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
                    patternLayout { artifact("v[revision]/[module]-[revision].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("com.k2fsa.sherpa", "sherpa-onnx") }
        }
    }
}
rootProject.name = "VoiceCaption"
include(":app")
