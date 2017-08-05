
// @GENERATOR:play-routes-compiler
// @SOURCE:/Users/vidhyasagar/Desktop/demo/cloudberry/neo/conf/routes
// @DATE:Sat Aug 05 11:58:53 PDT 2017

package controllers;

import router.RoutesPrefix;

public class routes {
  
  public static final controllers.ReverseAssets Assets = new controllers.ReverseAssets(RoutesPrefix.byNamePrefix());
  public static final controllers.ReverseCloudberry Cloudberry = new controllers.ReverseCloudberry(RoutesPrefix.byNamePrefix());

  public static class javascript {
    
    public static final controllers.javascript.ReverseAssets Assets = new controllers.javascript.ReverseAssets(RoutesPrefix.byNamePrefix());
    public static final controllers.javascript.ReverseCloudberry Cloudberry = new controllers.javascript.ReverseCloudberry(RoutesPrefix.byNamePrefix());
  }

}
