// Archivo de nivel PROYECTO: solo declara los plugins; cada módulo decide cuáles aplica.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.kapt) apply false
}

// Los archivos compilados se guardan fuera de la carpeta del proyecto (C:\Users\<usuario>\.museo-build),
// porque el proyecto está en OneDrive y su sincronización bloqueaba la carpeta build al compilar.
// La APK de prueba queda en C:\Users\<usuario>\.museo-build\MuseoDeLosMuseos\app\outputs\apk\debug\
allprojects {
    layout.buildDirectory.set(file("${System.getProperty("user.home")}/.museo-build/${rootProject.name}/${project.name}"))
}
