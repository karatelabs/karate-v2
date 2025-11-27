// API endpoint for items CRUD operations

// Sample items data (in real app, this would be in DB)
var items = [
    { id: 1, name: 'Apple', price: 1.50 },
    { id: 2, name: 'Banana', price: 0.75 },
    { id: 3, name: 'Cherry', price: 2.00 }
]

if (request.get) {
    // GET - list all or get single by ID
    var id = request.paramInt('id')
    if (id) {
        var item = items.filter(function(i) { return i.id === id })[0]
        if (item) {
            response.body = item
        } else {
            response.status = 404
            response.body = { error: 'Item not found', id: id }
        }
    } else {
        response.body = items
    }
}

if (request.post) {
    // POST - create new item
    var body = request.body
    var newItem = {
        id: items.length + 1,
        name: body.name,
        price: body.price
    }
    items.push(newItem)
    response.status = 201
    response.body = newItem
}

if (request.put) {
    // PUT - update item
    var id = request.paramInt('id')
    var body = request.body
    var item = items.filter(function(i) { return i.id === id })[0]
    if (item) {
        item.name = body.name || item.name
        item.price = body.price !== undefined ? body.price : item.price
        response.body = item
    } else {
        response.status = 404
        response.body = { error: 'Item not found' }
    }
}

if (request.delete) {
    // DELETE - remove item
    var id = request.paramInt('id')
    var index = -1
    for (var i = 0; i < items.length; i++) {
        if (items[i].id === id) {
            index = i
            break
        }
    }
    if (index >= 0) {
        items.splice(index, 1)
        response.body = { deleted: true, id: id }
    } else {
        response.status = 404
        response.body = { error: 'Item not found' }
    }
}
