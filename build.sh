#!/bin/sh

echo Building from scratch...
mvn clean
mvn dependency:copy-dependencies
mvn package

echo "Run the translator with: java -cp 'target/lib/*:target/translator-1.0-SNAPSHOT.jar' AnalyzerKt <PATH_TO_CONFIG>"
