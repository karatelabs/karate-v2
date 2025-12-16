Feature: Report Test Feature

Background:
* print 'in background'

@smoke @critical
Scenario: First passing test
* def x = 1
* match x == 1
* print 'first scenario passed'

@smoke @regression
Scenario: Second passing test with JSON
* def data = { name: 'test', value: 42 }
* match data.name == 'test'
* match data.value == 42

@regression
Scenario: Test with table
* def users = [{name: 'Alice', age: 30}, {name: 'Bob', age: 25}]
* match users[0].name == 'Alice'

Scenario: This one fails
* def a = 1
* match a == 2

@slow
Scenario: Another passing test
* def result = 'success'
* match result == 'success'
