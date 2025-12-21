Feature: Test karate.config API

  Scenario: verify karate.config returns configure settings
    * configure printEnabled = false
    * configure logPrettyRequest = true
    * match karate.config.printEnabled == false
    * match karate.config.logPrettyRequest == true

  Scenario: verify karate.config is read-only copy
    * configure printEnabled = true
    * def config1 = karate.config
    * configure printEnabled = false
    * def config2 = karate.config
    # Original reference should still have true (shallow copy)
    * match config1.printEnabled == true
    * match config2.printEnabled == false
