File dependencyTree = new File(basedir, "target/runtime-dependencies.txt")
if (!dependencyTree.isFile()) {
    throw new IllegalStateException("Runtime dependency tree was not generated: " + dependencyTree)
}

String tree = dependencyTree.getText("UTF-8")
[
        "canon-cr3-container",
        "canon-cr3-metadata",
        "canon-cr3-codec",
        "canon-cr3-processing",
        "canon-cr3-core",
        "canon-cr3-imageio"
].each { artifact ->
    if (!tree.contains("com.photographer.focusstack:" + artifact + ":")) {
        throw new IllegalStateException("Expected runtime artifact missing from dependency tree: " + artifact
                + "\n" + tree)
    }
}

[
        "canon-cr3-reference-testkit",
        "canon-cr3-research",
        "canon-cr3-maven-smoke"
].each { forbidden ->
    if (tree.contains("com.photographer.focusstack:" + forbidden + ":")) {
        throw new IllegalStateException("Test/research artifact leaked into external runtime tree: " + forbidden
                + "\n" + tree)
    }
}

return true
