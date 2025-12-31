function fn() {
  var webSocketUrl = karate.properties['karate.driver.webSocketUrl'];
  var serverUrl = karate.properties['karate.driver.serverUrl'];

  karate.log('karate-config: webSocketUrl =', webSocketUrl);
  karate.log('karate-config: serverUrl =', serverUrl);

  var config = {
    serverUrl: serverUrl,
    driverConfig: {
      webSocketUrl: webSocketUrl,
      timeout: 30000
    }
  };

  karate.configure('driver', config.driverConfig);

  return config;
}
