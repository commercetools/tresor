enablePlugins(ParadoxMaterialThemePlugin, ParadoxSitePlugin, GhpagesPlugin)

sourceDirectory in Paradox := baseDirectory.value / "docs"

ParadoxMaterialThemePlugin.paradoxMaterialThemeSettings(Paradox)

paradoxProperties += ("version" -> version.value)

mappings in makeSite ++= Seq(
  file("LICENSE") -> "LICENSE"
)

paradoxMaterialTheme in Paradox := {
  ParadoxMaterialTheme()
    .withColor("blue-grey", "blue-grey")
    .withCopyright("© tresor contributors")
    .withRepository(uri("https://github.com/adrobisch/tresor"))
    .withFont("Source Sans Pro", "Iosevka")
    .withLogoIcon("vpn_key")
}

git.remoteRepo := "git@github.com:adrobisch/tresor.git"
