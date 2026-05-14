<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\License;
use App\Models\LicenseActivation;
use App\Services\LicenseDeviceActivationService;
use App\Services\LicenseJwtService;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

class LicenseController extends Controller
{
    public function __construct(
        private LicenseJwtService $jwt,
        private LicenseDeviceActivationService $activation,
    ) {}

    public function activate(Request $request): JsonResponse
    {
        $data = $request->validate([
            'license_key' => ['required', 'string', 'min:4', 'max:256'],
            'device_id' => ['required', 'string', 'max:128'],
        ]);

        $salt = (string) config('licensing.key_salt');
        if ($salt === '') {
            return response()->json(['message' => 'Server license salt not configured'], 500);
        }

        $normalized = strtoupper(trim($data['license_key']));
        $keyHash = hash('sha256', $normalized.$salt);

        /** @var License|null $license */
        $license = License::query()->where('key_hash', $keyHash)->first();
        if ($license === null) {
            return response()->json(['message' => 'Invalid license key'], 404);
        }
        if ($license->app_account_id !== null) {
            return response()->json([
                'message' => 'This license is tied to an account. Sign in with phone, password, and license key.',
            ], 403);
        }
        if ($license->isRevoked()) {
            return response()->json(['message' => 'License revoked'], 403);
        }
        if ($license->isExpired()) {
            return response()->json(['message' => 'License expired'], 403);
        }

        $deviceId = trim($data['device_id']);

        try {
            $token = $this->activation->activateAndIssueToken($license, $deviceId);
        } catch (\RuntimeException $e) {
            if ($e->getMessage() === 'ACTIVATION_LIMIT') {
                return $this->activation->jsonActivationLimit();
            }
            throw $e;
        }

        return response()->json(['token' => $token]);
    }

    public function refresh(Request $request): JsonResponse
    {
        $data = $request->validate([
            'device_id' => ['required', 'string', 'max:128'],
        ]);

        $header = $request->header('Authorization', '');
        if (! is_string($header) || ! str_starts_with($header, 'Bearer ')) {
            return response()->json(['message' => 'Missing bearer token'], 401);
        }
        $raw = trim(substr($header, 7));
        if ($raw === '') {
            return response()->json(['message' => 'Missing bearer token'], 401);
        }

        try {
            $claims = $this->jwt->decode($raw);
        } catch (\InvalidArgumentException) {
            return response()->json(['message' => 'Invalid token'], 401);
        }

        $deviceId = trim($data['device_id']);
        if (($claims->did ?? '') !== $deviceId) {
            return response()->json(['message' => 'Device mismatch'], 403);
        }

        $licenseId = (int) ($claims->lid ?? $claims->sub ?? 0);
        /** @var License|null $license */
        $license = License::query()->find($licenseId);
        if ($license === null) {
            return response()->json(['message' => 'License not found'], 404);
        }
        if ($license->isRevoked()) {
            return response()->json(['message' => 'License revoked'], 403);
        }
        if ($license->isExpired()) {
            return response()->json(['message' => 'License expired'], 403);
        }

        $activation = LicenseActivation::query()
            ->where('license_id', $license->id)
            ->where('device_id', $deviceId)
            ->first();
        if ($activation === null) {
            return response()->json(['message' => 'Not activated on this device'], 403);
        }

        $activation->update(['last_refreshed_at' => now()]);
        $license->loadMissing('appAccount');
        $token = $this->jwt->issue($license, $deviceId, $license->appAccount);

        return response()->json(['token' => $token]);
    }
}
