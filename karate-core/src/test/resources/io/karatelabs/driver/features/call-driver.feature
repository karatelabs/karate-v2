Feature: Driver inheritance in called features
  Tests V1-compatible driver behavior:
  - Driver instance is inherited by called features
  - Driver config is inherited by called features
  - Driver is not closed until top-level scenario exits

Background:
* url serverUrl

Scenario: driver should work in called feature
* driver serverUrl + '/index.html'
* match driver.title == 'Karate Driver Test'
* print 'Main feature - calling sub-feature'
* call read('call-driver-sub.feature')
* print 'Back in main feature'
* match driver.title == 'Karate Driver Test'

@skipProvider
Scenario: called feature can init driver using inherited config
# This scenario does NOT init driver - calls a feature that inits driver
# The called feature should be able to init driver using inherited driverConfig
# Note: This scenario tests V1-style driver upward propagation which only works
# when no DriverProvider is configured. With DriverProvider, drivers are pooled
# and shouldn't propagate upward.
* print 'Main feature - not initializing driver'
* call read('call-config-inherit.feature')
* print 'Back in main - driver should still work'
* match driver.title == 'Karate Driver Test'
