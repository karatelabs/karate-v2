Feature: Driver inheritance in called features

Background:
* url serverUrl

Scenario: driver should work in called feature
* driver serverUrl + '/index.html'
* match driver.title == 'Karate Driver Test'
* print 'Main feature - calling sub-feature'
* call read('call-driver-sub.feature')
* print 'Back in main feature'
* match driver.title == 'Karate Driver Test'
