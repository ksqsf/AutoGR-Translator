#!/bin/bash

jenv exec java -cp 'target/lib/*:target/translator-1.0-SNAPSHOT.jar' AnalyzerKt "$@"
