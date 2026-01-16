@ignore
Feature: Test config inheritance in called features

Background:
* configure driver = driverConfig

Scenario: Verify config is inherited and driver works
# This feature is called by call-driver.feature
# It should inherit the driverConfig from the caller
* print 'call-config-inherit: verifying config inheritance'
* print 'call-config-inherit: serverUrl =', serverUrl
# Navigate using inherited driver
* driver serverUrl + '/index.html'
* match driver.title == 'Karate Driver Test'
* print 'call-config-inherit: driver works with inherited config'
