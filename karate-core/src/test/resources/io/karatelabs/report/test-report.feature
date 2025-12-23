Feature: User Management API

Background:
* print 'setting up test context'

@smoke @critical @auth
Scenario: User login succeeds with valid credentials
* def credentials = { username: 'admin', password: 'secret' }
* def response = { token: 'abc123', expires: 3600 }
* match response.token == 'abc123'
* print 'login successful'

@smoke @regression
Scenario: User profile returns correct data
* def user = { id: 1, name: 'John Doe', email: 'john@example.com', role: 'admin' }
* match user.name == 'John Doe'
* match user.role == 'admin'

@regression @security
Scenario: Unauthorized access is rejected
* def response = { status: 401, message: 'Unauthorized' }
* match response.status == 401

@wip
Scenario: This test is still in progress
* def a = 1
* match a == 2

@slow @integration
Scenario: Database sync completes successfully
* def result = 'sync complete'
* match result == 'sync complete'
* print 'database synced'

@embed @demo
Scenario: Embedded content showcase
* def apiResponse = { status: 'ok', users: [{id: 1, name: 'Alice'}, {id: 2, name: 'Bob'}] }
* karate.embed(apiResponse, 'application/json', 'API Response')
* karate.embed('Test completed at ' + java.time.LocalDateTime.now(), 'text/plain', 'Timestamp')
* karate.embed('<div style="padding:10px;background:#e8f5e9;border-radius:4px"><h3>Test Result</h3><p>All assertions passed!</p></div>', 'text/html', 'Result Summary')
* match apiResponse.status == 'ok'
