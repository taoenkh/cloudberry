
// @GENERATOR:play-routes-compiler
// @SOURCE:/Users/vidhyasagar/Desktop/demo/cloudberry/neo/conf/routes
// @DATE:Sat Aug 05 11:58:53 PDT 2017


package router {
  object RoutesPrefix {
    private var _prefix: String = "/"
    def setPrefix(p: String): Unit = {
      _prefix = p
    }
    def prefix: String = _prefix
    val byNamePrefix: Function0[String] = { () => prefix }
  }
}
