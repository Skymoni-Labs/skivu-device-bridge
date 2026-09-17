param()

# Package the generated artwork at Android's 108dp adaptive-layer size.
# This only resizes and applies launcher masks; the source artwork stays untouched.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$projectRoot = Split-Path -Parent $PSScriptRoot
$artDirectory = Join-Path $projectRoot 'design/icon'
$resourceDirectory = Join-Path $projectRoot 'app/src/main/res/drawable-xxxhdpi'
New-Item -ItemType Directory -Force -Path $resourceDirectory | Out-Null
$source = [System.Drawing.Bitmap]::FromFile((Join-Path $artDirectory 'phone-use-source.png'))
$background = [System.Drawing.ColorTranslator]::FromHtml('#211B3B')

function New-Canvas([int]$size) {
    return [System.Drawing.Bitmap]::new($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
}

function New-Graphics($bitmap) {
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    return $graphics
}

function Save-Png($bitmap, [string]$path) {
    $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
}

try {
    # 432px at xxxhdpi = 108dp. 12dp transparent margins keep the mark in
    # Android's central 66dp safe circle, including the overlapping cursor.
    $layer = New-Canvas 432
    $graphics = New-Graphics $layer
    try {
        $graphics.DrawImage($source, [System.Drawing.Rectangle]::new(48, 48, 336, 336))
        Save-Png $layer (Join-Path $resourceDirectory 'phone_use_icon_foreground.png')
    } finally {
        $graphics.Dispose()
        $layer.Dispose()
    }

    # Android normally reveals the central 72dp of its 108dp layers.
    # Exports below reproduce that viewport, without adding a baked-in margin.
    foreach ($variant in @(
        @{ Name = 'phone-use.png'; Size = 1024; Mask = 'square' },
        @{ Name = 'phone-use-preview.png'; Size = 384; Mask = 'rounded' },
        @{ Name = 'phone-use-round.png'; Size = 384; Mask = 'circle' }
    )) {
        $size = $variant.Size
        $bitmap = New-Canvas $size
        $graphics = New-Graphics $bitmap
        $mask = [System.Drawing.Drawing2D.GraphicsPath]::new()
        $brush = [System.Drawing.SolidBrush]::new($background)
        try {
            if ($variant.Mask -eq 'circle') {
                $mask.AddEllipse(0, 0, $size, $size)
            } elseif ($variant.Mask -eq 'rounded') {
                $diameter = [single]($size * 0.44)
                $far = [single]($size - $diameter)
                $mask.AddArc(0, 0, $diameter, $diameter, 180, 90)
                $mask.AddArc($far, 0, $diameter, $diameter, 270, 90)
                $mask.AddArc($far, $far, $diameter, $diameter, 0, 90)
                $mask.AddArc(0, $far, $diameter, $diameter, 90, 90)
                $mask.CloseFigure()
            } else {
                $mask.AddRectangle([System.Drawing.Rectangle]::new(0, 0, $size, $size))
            }
            $graphics.FillPath($brush, $mask)
            $graphics.SetClip($mask)
            $offset = [single](-$size / 12.0)
            $extent = [single]($size * 7.0 / 6.0)
            $graphics.DrawImage($source, [System.Drawing.RectangleF]::new($offset, $offset, $extent, $extent))
            Save-Png $bitmap (Join-Path $artDirectory $variant.Name)
        } finally {
            $mask.Dispose()
            $brush.Dispose()
            $graphics.Dispose()
            $bitmap.Dispose()
        }
    }
} finally {
    $source.Dispose()
}

Write-Output 'Exported Android foreground, 1024px artwork, and launcher mask previews.'
