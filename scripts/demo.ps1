param([uri]$BaseUrl = 'http://localhost:8080',
      [System.Management.Automation.PSCredential]$AdminCredential)

$ErrorActionPreference = 'Stop'
if (-not $BaseUrl.IsAbsoluteUri -or -not $BaseUrl.IsLoopback -or $BaseUrl.Scheme -notin @('http', 'https') -or
    $BaseUrl.AbsolutePath -ne '/' -or $BaseUrl.Query -or $BaseUrl.Fragment -or $BaseUrl.UserInfo) {
    throw 'Demo data is restricted to a loopback API URL, such as http://localhost:8080.'
}
$demoApiRoot = $BaseUrl.GetLeftPart([System.UriPartial]::Authority)
$demoAdminToken = ''

function Invoke-DemoRequest {
    param([string]$Method, [string]$Path, [object]$Body = $null,
          [int]$ExpectedStatus = 200, [string]$Key = '', [string]$AccessToken = $demoAdminToken)
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
    if ($AccessToken) { $options.Headers['Authorization'] = 'Bearer ' + $AccessToken }
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

if ($null -eq $AdminCredential) { $AdminCredential = Get-Credential -Message 'Local ADMIN account created by operator bootstrap' }
$adminLogin = (Invoke-DemoRequest POST '/api/auth/login' @{
    email = $AdminCredential.UserName; password = $AdminCredential.GetNetworkCredential().Password
}).Body
if ($adminLogin.user.role -ne 'ADMIN') { throw 'Demo requires an ADMIN account for product and inventory operations.' }
$demoAdminToken = $adminLogin.accessToken
$demoCustomerEmail = 'demo-' + [guid]::NewGuid().ToString('N') + '@example.com'
$demoCustomerPassword = 'demo-' + [guid]::NewGuid().ToString('N')
$null = Invoke-DemoRequest POST '/api/auth/register' @{ email = $demoCustomerEmail; password = $demoCustomerPassword } 201
$demoCustomerToken = (Invoke-DemoRequest POST '/api/auth/login' @{ email = $demoCustomerEmail; password = $demoCustomerPassword }).Body.accessToken
Write-Host 'Creating demo records through the local API. Each run creates one CUSTOMER, two products and two orders.'
Write-Host 'No startup seed, direct SQL, or automatic cleanup is performed.'
$keyboard = (Invoke-DemoRequest POST '/api/products' @{
    name = 'Demo Mechanical Keyboard'; description = 'Local portfolio demo'; price = 59.90; stockQuantity = 20
} 201).Body
$mouse = (Invoke-DemoRequest POST '/api/products' @{
    name = 'Demo USB Mouse'; description = 'Local portfolio demo'; price = 19.99; stockQuantity = 10
} 201).Body
$catalog = (Invoke-DemoRequest GET '/api/products?keyword=keyboard&minPrice=10&maxPrice=100&inStock=true&page=0&size=5&sort=price,asc').Body
$null = Invoke-DemoRequest PUT "/api/products/$($keyboard.id)" @{
    name = $keyboard.name; description = 'Metadata updated by local demo'; price = 59.90
}

$demoKey = 'demo-' + [guid]::NewGuid().ToString()
$orderBody = @{ items = @(@{ productId = $keyboard.id; quantity = 2 }, @{ productId = $mouse.id; quantity = 1 }) }
$created = Invoke-DemoRequest POST '/api/orders' $orderBody 201 $demoKey $demoCustomerToken
$retry = Invoke-DemoRequest POST '/api/orders' $orderBody 200 $demoKey $demoCustomerToken
if ($created.Body.id -ne $retry.Body.id -or $retry.Headers['Idempotency-Replayed'] -ne 'true') {
    throw 'The idempotency replay did not match the created order.'
}
$null = Invoke-DemoRequest POST '/api/orders' @{ items = @(@{ productId = $keyboard.id; quantity = 1 }) } 409 $demoKey $demoCustomerToken
$afterOrder = (Invoke-DemoRequest GET "/api/products/$($keyboard.id)").Body
if ($afterOrder.stockQuantity -ne 18) { throw 'Expected stock 18 after keyed creation and retry.' }
$metadataUpdated = (Invoke-DemoRequest PUT "/api/products/$($keyboard.id)" @{
    name = $keyboard.name; description = 'Metadata updated after ordering'; price = 59.90
}).Body
if ($metadataUpdated.stockQuantity -ne 18) { throw 'Metadata PUT must preserve stock after ordering.' }
$null = Invoke-DemoRequest GET "/api/orders/$($created.Body.id)" -AccessToken $demoCustomerToken
$null = Invoke-DemoRequest POST "/api/orders/$($created.Body.id)/cancel"
$cancelReplay = (Invoke-DemoRequest POST '/api/orders' $orderBody 200 $demoKey $demoCustomerToken).Body
if ($cancelReplay.status -ne 'CREATED') { throw 'A replay must retain the original creation snapshot.' }

$fulfillment = (Invoke-DemoRequest POST '/api/orders' @{
    items = @(@{ productId = $keyboard.id; quantity = 1 })
} 201 -AccessToken $demoCustomerToken).Body
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
    CustomerUserId = $created.Body.userId
    MouseId = $mouse.id
    CancelledOrderId = $created.Body.id
    DeliveredOrderId = $fulfillment.id
    IdempotencyKey = $demoKey
    FinalKeyboardStock = $restocked.stockQuantity
    KeyboardMovementCount = $history.totalElements
    MatchingCatalogProducts = $catalog.totalElements
    SwaggerUi = $demoApiRoot + '/swagger-ui/index.html'
}
