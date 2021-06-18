enablePlugins(ParadoxSitePlugin, GhpagesPlugin, ParadoxMaterialThemePlugin)

Paradox / sourceDirectory := baseDirectory.value / "docs"

paradoxProperties += ("version" -> version.value)

makeSite / mappings ++= Seq(
  file("LICENSE") -> "LICENSE"
)

Paradox / paradoxMaterialTheme := {
  ParadoxMaterialTheme()
    .withColor("blue-grey", "blue-grey")
    .withCopyright("© tresor contributors")
    .withRepository(uri("https://github.com/adrobisch/tresor"))
    .withFont("Source Sans Pro", "Iosevka")
    .withLogoIcon("vpn_key")
}

git.remoteRepo := "git@github.com:adrobisch/tresor.git"
