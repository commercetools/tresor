enablePlugins(ParadoxSitePlugin, GhpagesPlugin)

paradoxProperties += ("version" -> version.value)

makeSite / mappings ++= Seq(
  file("LICENSE") -> "LICENSE"
)

git.remoteRepo := "git@github.com:commercetools/tresor.git"
