function fn() {
  var serverUrl = karate.properties['karate.serverUrl'] || 'http://localhost:18899';
  karate.log('Using serverUrl:', serverUrl);

  // Configure driver for local Chrome with headless mode
  karate.configure('driver', {
    type: 'chrome',
    headless: true,
    userDataDir: 'target/chrome-temp-test'
  });

  return {
    serverUrl: serverUrl
  };
}
