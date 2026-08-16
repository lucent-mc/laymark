// Root project carries no code. Module structure and the reasoning behind it are in
// docs/laymark-0.x-spec.md section 4.

tasks.register("purity") {
    group = "verification"
    description = "Runs every module's purity check without building artifacts."
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("purityCheck") })
}
