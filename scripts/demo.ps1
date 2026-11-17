param([uri]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Stop'
if (-not $BaseUrl.IsAbsoluteUri -or -not $BaseUrl.IsLoopback -or $BaseUrl.Scheme -notin @('http', 'https') -or
    $BaseUrl.AbsolutePath -ne '/' -or $BaseUrl.Query -or $BaseUrl.Fragment -or $BaseUrl.UserInfo) {
    throw 'Demo data is restricted to a loopback API URL, such as http://localhost:8080.'
}
$demoApiRoot = $BaseUrl.GetLeftPart([System.UriPartial]::Authority)

function Invoke-DemoRequest {
    param([string]$Method, [string]$Path, [object]$Body = $null,
          [int]$ExpectedStatus = 200, [string]$Key = '')
    $options = @{
        Uri = $demoApiRoot + $Path
        Method = $Method
        UseBasicParsing = $true
        TimeoutSec = 30
        Headers = @{ Accept = 'application/json' }
    }
    if ($null -ne $Body) {
        $options.ContentType = 'application/json'
        $options.Body = ConvertTo-Json -InputObject $Body -Depth 8 -Compress
    }
    if ($Key) { $options.Headers['Idempotency-Key'] = $Key }
    try {
        $response = Invoke-WebRequest @options
    } catch {
        if ($ExpectedStatus -ge 400 -and $_.Exception.Response -and
            [int]$_.Exception.Response.StatusCode -eq $ExpectedStatus) {
            Write-Host "$Method $Path -> $ExpectedStatus (expected error)"
            return
        }
        throw
    }
    if ([int]$response.StatusCode -ne $ExpectedStatus) {
        throw "$Method $Path returned $($response.StatusCode); expected $ExpectedStatus."
    }
    Write-Host "$Method $Path -> $ExpectedStatus"
    return [pscustomobject]@{
        Body = if ($response.Content) { ConvertFrom-Json $response.Content } else { $null }
        Headers = $response.Headers
    }
}

Write-Host 'Creating demo records through the local API. Each run creates two new products and two orders.'
Write-Host 'No startup seed, direct SQL, or automatic cleanup is performed.'
$keyboard = (Invoke-DemoRequest POST '/api/products' @{
    name = 'Demo Mechanical Keyboard'; description = 'Local portfolio demo'; price = 59.90; stockQuantity = 20
} 201).Body
$mouse = (Invoke-DemoRequest POST '/api/products' @{
    name = 'Demo USB Mouse'; description = 'Local portfolio demo'; price = 19.99; stockQuantity = 10
} 201).Body
$catalog = (Invoke-DemoRequest GET '/api/products?keyword=keyboard&minPrice=10&maxPrice=100&inStock=true&page=0&size=5&sort=price,asc').Body
$null = Invoke-DemoRequest PUT "/api/products/$($keyboard.id)" @{
    name = $keyboard.name; description = 'Metadata updated by local demo'; price = 59.90; stockQuantity = 20
}

$demoKey = 'demo-' + [guid]::NewGuid().ToString()
$orderBody = @{ items = @(@{ productId = $keyboard.id; quantity = 2 }, @{ productId = $mouse.id; quantity = 1 }) }
$created = Invoke-DemoRequest POST '/api/orders' $orderBody 201 $demoKey
$retry = Invoke-DemoRequest POST '/api/orders' $orderBody 200 $demoKey
if ($created.Body.id -ne $retry.Body.id -or $retry.Headers['Idempotency-Replayed'] -ne 'true') {
    throw 'The idempotency replay did not match the created order.'
}
$null = Invoke-DemoRequest POST '/api/orders' @{ items = @(@{ productId = $keyboard.id; quantity = 1 }) } 409 $demoKey
$afterOrder = (Invoke-DemoRequest GET "/api/products/$($keyboard.id)").Body
if ($afterOrder.stockQuantity -ne 18) { throw 'Expected stock 18 after keyed creation and retry.' }
$null = Invoke-DemoRequest GET "/api/orders/$($created.Body.id)"
$null = Invoke-DemoRequest POST "/api/orders/$($created.Body.id)/cancel"
$cancelReplay = (Invoke-DemoRequest POST '/api/orders' $orderBody 200 $demoKey).Body
if ($cancelReplay.status -ne 'CREATED') { throw 'A replay must retain the original creation snapshot.' }

$fulfillment = (Invoke-DemoRequest POST '/api/orders' @{
    items = @(@{ productId = $keyboard.id; quantity = 1 })
} 201).Body
foreach ($action in @('confirm', 'ship', 'deliver')) {
    $null = Invoke-DemoRequest POST "/api/orders/$($fulfillment.id)/$action"
}
$delivered = (Invoke-DemoRequest GET "/api/orders/$($fulfillment.id)").Body
if ($delivered.status -ne 'DELIVERED') { throw 'Expected the fulfillment order to be delivered.' }
$restocked = (Invoke-DemoRequest POST "/api/products/$($keyboard.id)/restock" @{
    quantity = 5; reason = 'Local demo supplier delivery'
}).Body
$history = (Invoke-DemoRequest GET "/api/products/$($keyboard.id)/stock-movements?page=0&size=20&sort=createdAt,asc").Body
$null = Invoke-DemoRequest POST '/api/orders' @{ items = @(@{ productId = $keyboard.id; quantity = 0 }) } 400
$null = Invoke-DemoRequest POST '/api/orders' @{ items = @(@{ productId = $keyboard.id; quantity = 1000000 }) } 409
$null = Invoke-DemoRequest DELETE "/api/products/$($keyboard.id)" $null 409
if ($restocked.stockQuantity -ne 24 -or $history.totalElements -ne 5) {
    throw 'Expected final keyboard stock 24 and five inventory movements.'
}
Write-Host 'Demo completed. Inspect these IDs in Swagger UI; records remain in your local database.'
[pscustomobject]@{
    KeyboardId = $keyboard.id
    MouseId = $mouse.id
    CancelledOrderId = $created.Body.id
    DeliveredOrderId = $fulfillment.id
    IdempotencyKey = $demoKey
    FinalKeyboardStock = $restocked.stockQuantity
    KeyboardMovementCount = $history.totalElements
    MatchingCatalogProducts = $catalog.totalElements
    SwaggerUi = $demoApiRoot + '/swagger-ui/index.html'
}
