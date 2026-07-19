File dependencyTree = new File(basedir, "target/runtime-dependencies.txt")
if (!dependencyTree.isFile()) {
    throw new IllegalStateException("Runtime dependency tree was not generated: " + dependencyTree)
}

String tree = dependencyTree.getText("UTF-8")
Set<String> expected = [
        "canon-cr3-container",
        "canon-cr3-metadata",
        "canon-cr3-codec",
        "canon-cr3-processing",
        "canon-cr3-core",
        "canon-cr3-imageio"
] as Set

Set<String> resolved = [] as Set
def matcher = tree =~ /com\.photographer\.focusstack:(canon-cr3-[^:\s]+):/
matcher.each { match -> resolved.add(match[1]) }

if (resolved != expected) {
    Set<String> missing = new TreeSet<>(expected)
    missing.removeAll(resolved)
    Set<String> unexpected = new TreeSet<>(resolved)
    unexpected.removeAll(expected)
    throw new IllegalStateException("Unexpected external CR3 runtime graph"
            + "\nmissing=" + missing
            + "\nunexpected=" + unexpected
            + "\n" + tree)
}

[
        "canon-cr3-reference-testkit",
        "canon-cr3-research",
        "canon-cr3-maven-smoke",
        "canon-cr3-benchmark",
        "canon-cr3-cli"
].each { forbidden ->
    if (tree.contains("com.photographer.focusstack:" + forbidden + ":")) {
        throw new IllegalStateException("Non-library artifact leaked into external runtime tree: "
                + forbidden + "\n" + tree)
    }
}

return true
