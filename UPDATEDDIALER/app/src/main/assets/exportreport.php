<?php
// exportreport.php

header('Content-Type: application/json');

// Ensure the reports directory exists
$reportsDir = __DIR__ . '/reports/';
if (!is_dir($reportsDir)) {
    if (!mkdir($reportsDir, 0775, true)) {
        http_response_code(500);
        echo json_encode(['error' => 'Failed to create reports directory', 'dir' => $reportsDir]);
        exit;
    }
}

// Get POST data
$input = json_decode(file_get_contents('php://input'), true);
if (!$input || !isset($input['filename']) || !isset($input['csv'])) {
    http_response_code(400);
    echo json_encode(['error' => 'Missing filename or csv', 'input' => $input]);
    exit;
}

// Sanitize filename: allow only safe characters
$filename = preg_replace('/[^A-Za-z0-9 _\-\.]/', '_', basename($input['filename']));
if (empty($filename)) {
    http_response_code(400);
    echo json_encode(['error' => 'Invalid filename']);
    exit;
}

$csvData = base64_decode($input['csv']);
if ($csvData === false) {
    http_response_code(400);
    echo json_encode(['error' => 'Invalid base64 data']);
    exit;
}

// Save the file
$filePath = $reportsDir . $filename;
if (file_put_contents($filePath, $csvData) !== false) {
    echo json_encode(['success' => true, 'path' => 'reports/' . $filename, 'size' => strlen($csvData)]);
} else {
    http_response_code(500);
    echo json_encode(['error' => 'Failed to save file', 'filePath' => $filePath, 'dirWritable' => is_writable($reportsDir)]);
}
?>