[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9_-]+$')]
    [string]$CharacterKey,
    [string]$BaseUri = 'http://localhost:47831',
    [string]$InputPath,
    [string]$OutputPath,
    [switch]$CopyToClipboard
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$resourceRoot = Join-Path $projectRoot 'src/main/resources'
$promptFile = Join-Path $projectRoot 'docs/character-formula-generation-prompt.md'
$promptTemplate = Get-Content -LiteralPath $promptFile -Raw -Encoding utf8
$promptMatch = [regex]::Match($promptTemplate, '(?ms)^---[ \t]*\r?\n(?<instructions>.*?)^INPUT DATA TO PROCESS[ \t]*\r?$')
if (-not $promptMatch.Success) {
    throw "The prompt document must contain its separator and 'INPUT DATA TO PROCESS' heading."
}

if ($InputPath) {
    $character = Get-Content -LiteralPath $InputPath -Raw | ConvertFrom-Json
} else {
    $character = Invoke-RestMethod -Uri "$($BaseUri.TrimEnd('/'))/api/characters/$CharacterKey"
}
if ($character.key -cne $CharacterKey) {
    throw "Requested '$CharacterKey', but the supplied character data is '$($character.key)'."
}

$trimmed = [ordered]@{
    key = $character.key
    name = $character.name
    element = $character.element
    weapon = $character.weapon
    region = $character.region
    rarity = $character.rarity
    ascensionStatType = $character.ascensionStatType
    stats = $character.stats
    combatTalents = @($character.combatTalents | Select-Object key, kind, name, description, attributes)
    passiveTalents = @($character.passiveTalents | Select-Object key, name, description)
}

$talentResource = $null
$talentReferences = [ordered]@{}
foreach ($relativePath in @("data/characters/data/$CharacterKey.json", "data/damage-talent-data-v2/$CharacterKey.json")) {
    $candidate = Join-Path $resourceRoot $relativePath
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
    $raw = Get-Content -LiteralPath $candidate -Raw | ConvertFrom-Json
    foreach ($talentKey in @('combat1', 'combat2', 'combat3')) {
        $talent = $raw.talents.$talentKey
        if ($talent -and $talent.attributes.parameters) {
            $talentReferences[$talentKey] = [ordered]@{
                name = $talent.name
                attributes = [ordered]@{
                    labels = @($talent.attributes.labels)
                    parameters = $talent.attributes.parameters
                }
            }
        }
    }
    if ($talentReferences.Count -gt 0) {
        $talentResource = $relativePath
        break
    }
}

$damageCatalog = Get-Content -LiteralPath (Join-Path $resourceRoot 'data/damage-formulas-v2/damage-types.json') -Raw | ConvertFrom-Json
$teamRules = Get-Content -LiteralPath (Join-Path $resourceRoot 'data/damage-formulas-v2/team-rules.json') -Raw | ConvertFrom-Json
$gaps = @('Constellation descriptions are not guaranteed by the character endpoint; research missing descriptions on the public web.')
if (-not $talentResource) {
    $gaps += 'No packaged talent parameter mapping was found. Do not invent paramN identifiers; use verified level tables through the documented inline fallback.'
}
$packet = [ordered]@{
    character = $trimmed
    formulaAuthoring = [ordered]@{
        schemaVersion = 3
        fileName = "$CharacterKey.json"
        characterDataResource = $talentResource
        talentReferences = $talentReferences
        sharedDamageTypes = @($damageCatalog.damageTypes)
        hexereiCharacters = @($teamRules.hexereiCharacters)
        instructions = @(
            'The generator cannot access the API or local files. All local references needed for generation are supplied here.'
            'Use the supplied packaged resource and exact parameter keys. Do not duplicate these tables as inline rounded web values.'
            'Generate compact per-hit damage classes, not one formula/effect per identical projectile slot.'
            'Research missing mechanics/constellations on the public web. The user will validate locally afterward.'
        )
        dataGaps = $gaps
    }
}

if (-not $OutputPath) { $OutputPath = Join-Path (Get-Location).Path ".tmp/generated/$CharacterKey-formula-input.json" }
$json = $packet | ConvertTo-Json -Depth 60
$resolvedOutputPath = [System.IO.Path]::GetFullPath($OutputPath)
$promptOutputPath = [System.IO.Path]::ChangeExtension($resolvedOutputPath, 'prompt.md')
$combinedPrompt = [string]::Join("`n", @(
    $promptMatch.Groups['instructions'].Value.Trim()
    ''
    'INPUT DATA TO PROCESS'
    ''
    "Generate the complete, compact character definition for $($character.name) ($CharacterKey)."
    'Use the exact packaged talent references and shared catalogs in this packet. Public-web research is authorized for missing descriptions and mechanics. The user will validate locally afterward.'
    'An existing formula has not been supplied: generate a new definition following the compact format contract. Do not treat the illustrative Chasca example as the requested character or as complete coverage.'
    ''
    'Exported input packet:'
    '```json'
    $json
    '```'
))
$utf8 = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($resolvedOutputPath, $json, $utf8)
[System.IO.File]::WriteAllText($promptOutputPath, $combinedPrompt, $utf8)
if ($CopyToClipboard) {
    $combinedPrompt | Set-Clipboard
    Write-Output 'Copied the complete prompt and character data to the clipboard.'
}
Write-Output "Saved generator input: $resolvedOutputPath"
Write-Output "Saved complete prompt: $promptOutputPath"
