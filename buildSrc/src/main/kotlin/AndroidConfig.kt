import org.gradle.api.JavaVersion as GradleJavaVersion

object AndroidConfig {
    const val COMPILE_SDK = 36
    // Below API 24, D8 desugars interface default methods, turning
    // kotlinx.serialization's GeneratedSerializer.typeParametersSerializers() abstract. Extension
    // APKs are dexed against the real default method, so their serializers implement nothing and
    // every decode throws AbstractMethodError. 24 would be enough; 26 matches Mihon, which is what
    // the extension ecosystem is built against.
    const val MIN_SDK = 26
    const val TARGET_SDK = 36
    const val NDK = "27.2.12479018"
    val JavaVersion = GradleJavaVersion.VERSION_17
}
