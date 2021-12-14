# AutoGR Translator
This repository contains the source code of the analyzer as described in our VLDB 2021 paper [AutoGR: Automated Geo-Replication with Fast System Performance and Preserved Application Semantics](https://dl.acm.org/doi/abs/10.14778/3461535.3461541).

The translator is the "frontend" part of our static analyzer, RIGI.

## Build and Usage

First, install OpenJDK 8 and configure Maven.

```sh
mvn dependency:copy-dependencies
mvn package
```

The dependencies are copied to `target/lib` and the resulting JAR can be found at `target/translator-*.jar`. The main class is `AnalyzerKt`.

```sh
java -cp 'target/translator-VERSION.jar:target/lib/*' AnalyzerKt <CONFIG>
```

## Workflow

First, compile the original project to get the class files. The translator will need them to compute types and the class hierarchy.

Translator:

1. Read the configuration provided by the user.
2. Parse all Java files recursively under the specified project, excluding `exclude_classes` and `exclude`.
3. Apply interprocedural analysis to determine which methods are "effectual", i.e. committing effects.
4. Build Control Flow Graphs for all methods.
5. Collect effectual paths of effectual methods, and transform them into "atoms", an internal representation of a DB query.
6. Translate the atoms into Z3Py code.

Then, run this Z3Py file to compute restriction pairs.

Finally, integrate the original application into a pre-existing platform (e.g. Olisipo).

## Configuration

The translator accepts a limited set of options. We provide the configuration files for the applications we studied.
HealthPlus.yml and SmallBank.yml are commented.

When in doubt, read the source code. (Apologize for the inconvenience!)

### Class paths

As is stated above, the translator needs the class files to compute types and the class hierarchy.
Therefore, you will probably want to set the class path to include *anything the original project requires to run*.

You can either set the class path by Java `-cp` option, or set `additional_class_paths` in the config file.

## Customize

Wild applications are wildly diverse, and the translator shall be extended. The basic workflow is easy:

1. Create a new file `appSemantics/YourAppSemantics.kt`.
2. Define a `register()` function there, which adds new entries into `knownSemantics`.
3. In the config file, add `YourApp` to `additional_semantics`.

That's it! The interpreter will invoke your code when necessary. The original HealthPlus is a good example. Please see `appSemantics/HealthPlusSemantics.kt`.

Before coding, you should understand `AbstractValue`, which is the core of the translator. Essentially, it is a unified representation of  Java and SQL expressions. The most significant ones are:
- `Unknown`: an unknown value.
- `Data`: a Java object.

## Optimizations

The translator incorporates two optimizations. For details, refer to our paper.

## Assumptions

1. In Interpreter, obj.setField is considered to mutate obj.
