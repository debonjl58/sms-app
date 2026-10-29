#!/bin/bash
# Exécutez ce script pour générer le vrai wrapper Gradle

cd android-client

# Télécharge Gradle temporairement pour générer le wrapper
if ! command -v gradle &> /dev/null; then
    echo "Téléchargement de Gradle..."
    curl -L https://services.gradle.org/distributions/gradle-8.2-bin.zip -o gradle-temp.zip
    unzip -q gradle-temp.zip
    ./gradle-8.2/bin/gradle wrapper --gradle-version 8.2
    rm -rf gradle-8.2 gradle-temp.zip
else
    gradle wrapper --gradle-version 8.2
fi

# Convertit en LF (Unix)
dos2unix gradlew 2>/dev/null || sed -i 's/\r$//' gradlew

echo "✅ Wrapper Gradle généré avec succès"
echo "   - gradle-wrapper.jar: $(ls -lh gradle/wrapper/gradle-wrapper.jar | awk '{print $5}')"
echo "   - gradlew: $(file gradlew)"