Feature: Second Test Feature

@api
Scenario: API test simulation
* def response = { status: 200, body: 'ok' }
* match response.status == 200

@api @slow
Scenario: Another API test
* def headers = { 'Content-Type': 'application/json' }
* match headers['Content-Type'] == 'application/json'
