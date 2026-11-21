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
$demoCustomer = (Invoke-DemoRequest POST '/api/auth/register' @{ email = $demoCustomerEmail; password = $demoCustomerPassword } 201).Body
$demoCustomerToken = (Invoke-DemoRequest POST '/api/auth/login' @{ email = $demoCustomerEmail; password = $demoCustomerPassword }).Body.accessToken
Write-Host 'Creating demo records through the local API. Each run creates one CUSTOMER, two products and two orders.'
Write-Host 'No startup seed, direct SQL, or automatic cleanup is performed.'
$demoRunId = [guid]::NewGuid().ToString('N').Substring(0, 8)
$keyboard = (Invoke-DemoRequest POST '/api/products' @{
    name = "Demo Mechanical Keyboard $demoRunId"; description = 'Local portfolio demo'; price = 59.90; stockQuantity = 20
} 201).Body
$mouse = (Invoke-DemoRequest POST '/api/products' @{
    name = "Demo USB Mouse $demoRunId"; description = 'Local portfolio demo'; price = 19.99; stockQuantity = 10
} 201).Body
$demoKeyword = [uri]::EscapeDataString($keyboard.name.ToUpperInvariant())
$demoSearchPath = "/api/products?keyword=$demoKeyword&minPrice=10&maxPrice=100&inStock=true&page=0&size=1&sort=price,asc"
$catalog = (Invoke-DemoRequest GET $demoSearchPath -AccessToken '').Body
if ($catalog.totalElements -ne 1 -or $catalog.content[0].id -ne $keyboard.id) {
    throw 'Case-insensitive search and filtered pagination must find this run''s unique keyboard.'
}
$null = Invoke-DemoRequest PUT "/api/products/$($keyboard.id)" @{
    name = $keyboard.name; description = 'Metadata updated by local demo'; price = 59.90
}

$demoKey = 'demo-' + [guid]::NewGuid().ToString()
$orderBody = @{ items = @(@{ productId = $keyboard.id; quantity = 2 }, @{ productId = $mouse.id; quantity = 1 }) }
$created = Invoke-DemoRequest POST '/api/orders' $orderBody 201 $demoKey $demoCustomerToken
$retry = Invoke-DemoRequest POST '/api/orders' $orderBody 200 $demoKey $demoCustomerToken
if ($created.Body.id -ne $retry.Body.id -or $retry.Headers['Idempotency-Replayed'] -ne 'true' -or
    $created.Headers['Idempotency-Replayed'] -ne 'false' -or $created.Body.userId -ne $demoCustomer.id -or
    [decimal]$created.Body.totalAmount -ne [decimal]139.79) {
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
$ownCreated = (Invoke-DemoRequest GET '/api/orders?status=CREATED&page=0&size=1' -AccessToken $demoCustomerToken).Body
if ($ownCreated.totalElements -ne 1 -or $ownCreated.content[0].id -ne $created.Body.id -or
    $ownCreated.content[0].PSObject.Properties.Name -contains 'items') {
    throw 'Customer listing must contain its one new order as a summary without items.'
}
$null = Invoke-DemoRequest GET '/api/orders' -ExpectedStatus 401 -AccessToken ''
$null = Invoke-DemoRequest POST "/api/orders/$($created.Body.id)/confirm" -ExpectedStatus 403 -AccessToken $demoCustomerToken
$expectedStates = @('CONFIRMED', 'SHIPPED', 'DELIVERED')
$actions = @('confirm', 'ship', 'deliver')
for ($index = 0; $index -lt $actions.Count; $index++) {
    $transition = (Invoke-DemoRequest POST "/api/orders/$($created.Body.id)/$($actions[$index])").Body
    $currentProduct = (Invoke-DemoRequest GET "/api/products/$($keyboard.id)" -AccessToken '').Body
    if ($transition.status -ne $expectedStates[$index] -or $currentProduct.stockQuantity -ne 18) {
        throw 'Lifecycle actions must advance the state without another stock deduction.'
    }
}
$delivered = (Invoke-DemoRequest GET "/api/orders/$($created.Body.id)" -AccessToken $demoCustomerToken).Body
if ($delivered.status -ne 'DELIVERED') { throw 'Expected the fulfillment order to be delivered.' }
$null = Invoke-DemoRequest POST "/api/orders/$($created.Body.id)/cancel" -ExpectedStatus 409
$null = Invoke-DemoRequest POST "/api/orders/$($created.Body.id)/deliver" -ExpectedStatus 409
$deliveredPage = (Invoke-DemoRequest GET '/api/orders?status=DELIVERED&page=0&size=1' -AccessToken $demoCustomerToken).Body
if ($deliveredPage.totalElements -ne 1 -or $deliveredPage.content[0].id -ne $delivered.id) { throw 'Delivered listing filter failed.' }

# Availability only controls new sales. Repeating PATCH must not change inventory/history.
foreach ($repeat in 1..2) {
    $inactive = (Invoke-DemoRequest PATCH "/api/products/$($keyboard.id)/availability" @{ active = $false }).Body
    if ($inactive.active -or $inactive.stockQuantity -ne 18) { throw 'Deactivation must preserve inventory.' }
}
$null = Invoke-DemoRequest GET "/api/products/$($keyboard.id)" -ExpectedStatus 404 -AccessToken ''
$hiddenCatalog = (Invoke-DemoRequest GET $demoSearchPath -AccessToken '').Body
if ($hiddenCatalog.totalElements -ne 0) { throw 'Inactive products must be hidden from the public catalog.' }
$adminCatalog = (Invoke-DemoRequest GET "/api/admin/products?active=false&keyword=$demoKeyword&page=0&size=1").Body
if ($adminCatalog.totalElements -ne 1 -or $adminCatalog.content[0].id -ne $keyboard.id) { throw 'ADMIN must still find inactive products.' }
$inactiveHistory = (Invoke-DemoRequest GET "/api/products/$($keyboard.id)/stock-movements").Body
if ($inactiveHistory.totalElements -ne 2) { throw 'Repeated availability changes must not create inventory movements.' }
$secondKey = 'demo-' + [guid]::NewGuid().ToString()
$secondBody = @{ items = @(@{ productId = $keyboard.id; quantity = 1 }) }
$null = Invoke-DemoRequest POST '/api/orders' $secondBody 409 $secondKey $demoCustomerToken
$creationSnapshot = (Invoke-DemoRequest POST '/api/orders' $orderBody 200 $demoKey $demoCustomerToken).Body
if ($creationSnapshot.status -ne 'CREATED') { throw 'A replay must retain the creation snapshot after delivery/deactivation.' }
$retained = (Invoke-DemoRequest GET "/api/orders/$($delivered.id)" -AccessToken $demoCustomerToken).Body
if ($retained.status -ne 'DELIVERED') { throw 'Existing orders must remain readable after product deactivation.' }

$adjusted = (Invoke-DemoRequest POST "/api/products/$($keyboard.id)/adjust-stock" @{
    delta = -3; reason = 'Local demo damaged products'
}).Body
if ($adjusted.stockQuantity -ne 15 -or $adjusted.active) { throw 'Negative adjustment must preserve inactive status and remove three units.' }
$restocked = (Invoke-DemoRequest POST "/api/products/$($keyboard.id)/restock" @{
    quantity = 5; reason = 'Local demo supplier delivery'
}).Body
if ($restocked.stockQuantity -ne 20 -or $restocked.active) { throw 'Restock must remain compatible with inactive products.' }
$positiveAdjustment = (Invoke-DemoRequest POST "/api/products/$($keyboard.id)/adjust-stock" @{
    delta = 2; reason = 'Local demo stock count correction'
}).Body
if ($positiveAdjustment.stockQuantity -ne 22 -or $positiveAdjustment.active) { throw 'Positive adjustment must add two units without reactivating.' }
$reactivated = (Invoke-DemoRequest PATCH "/api/products/$($keyboard.id)/availability" @{ active = $true }).Body
if (-not $reactivated.active -or $reactivated.stockQuantity -ne 22) { throw 'Reactivation must preserve corrected inventory.' }
# The earlier inactive-product failure rolled back the key; the same intended order can now succeed.
$cancellable = (Invoke-DemoRequest POST '/api/orders' $secondBody 201 $secondKey $demoCustomerToken).Body
$null = Invoke-DemoRequest POST "/api/orders/$($cancellable.id)/cancel"
$null = Invoke-DemoRequest POST "/api/orders/$($cancellable.id)/cancel" -ExpectedStatus 409
$cancelReplay = (Invoke-DemoRequest POST '/api/orders' $secondBody 200 $secondKey $demoCustomerToken).Body
if ($cancelReplay.id -ne $cancellable.id -or $cancelReplay.status -ne 'CREATED') { throw 'Cancelled order replay must retain the creation snapshot.' }
$listed = (Invoke-DemoRequest GET '/api/orders?page=0&size=1' -AccessToken $demoCustomerToken).Body
$secondPage = (Invoke-DemoRequest GET '/api/orders?page=1&size=1' -AccessToken $demoCustomerToken).Body
if ($listed.totalElements -ne 2 -or $listed.totalPages -ne 2 -or $listed.content[0].id -ne $cancellable.id -or
    $secondPage.content[0].id -ne $delivered.id) { throw 'Customer pagination must return its two orders in newest-first order.' }
$null = Invoke-DemoRequest GET '/api/orders?status=DELIVERED&page=0&size=20' # ADMIN listing
$null = Invoke-DemoRequest POST "/api/products/$($keyboard.id)/adjust-stock" @{ delta = 0; reason = 'Invalid adjustment' } 400
$null = Invoke-DemoRequest POST "/api/products/$($keyboard.id)/adjust-stock" @{ delta = -2147483648; reason = 'Would make stock negative' } 409
$null = Invoke-DemoRequest POST "/api/products/$($keyboard.id)/adjust-stock" @{ delta = 1; reason = 'Unauthorized adjustment' } 403 -AccessToken $demoCustomerToken
$null = Invoke-DemoRequest POST '/api/orders' @{ items = @(@{ productId = $keyboard.id; quantity = 0 }) } 400
$null = Invoke-DemoRequest POST '/api/orders' @{ items = @(@{ productId = $keyboard.id; quantity = 1000000 }) } 409
$null = Invoke-DemoRequest DELETE "/api/products/$($keyboard.id)" $null 409
$finalProduct = (Invoke-DemoRequest GET "/api/products/$($keyboard.id)" -AccessToken '').Body
$history = (Invoke-DemoRequest GET "/api/products/$($keyboard.id)/stock-movements?page=0&size=20&sort=createdAt,asc").Body
if ($finalProduct.stockQuantity -ne 22 -or $history.totalElements -ne 7 -or
    ($history.content.quantityDelta -join ',') -ne '20,-2,-3,5,2,-1,1') {
    throw 'Expected final keyboard stock 22 and seven inventory movements with the exact signed deltas.'
}
$previousStock = 0L
foreach ($movement in $history.content) {
    if ($movement.stockBefore -ne $previousStock -or $movement.stockAfter -ne ($previousStock + $movement.quantityDelta)) {
        throw 'Inventory history must form a continuous before/delta/after chain.'
    }
    $previousStock = [long]$movement.stockAfter
}
Write-Host 'Demo completed. Inspect these IDs in Swagger UI; records remain in your local database.'
[pscustomobject]@{
    KeyboardId = $keyboard.id
    CustomerUserId = $created.Body.userId
    MouseId = $mouse.id
    CancelledOrderId = $cancellable.id
    DeliveredOrderId = $delivered.id
    IdempotencyKey = $demoKey
    FinalKeyboardStock = $finalProduct.stockQuantity
    KeyboardMovementCount = $history.totalElements
    MatchingCatalogProducts = $catalog.totalElements
    CustomerOrderCount = $listed.totalElements
    ProductActive = $finalProduct.active
    SwaggerUi = $demoApiRoot + '/swagger-ui/index.html'
}
