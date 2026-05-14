<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\AppAccount;
use App\Models\License;
use App\Services\LicenseDeviceActivationService;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

class AuthController extends Controller
{
    public function __construct(
        private LicenseDeviceActivationService $activation,
    ) {}

    public function login(Request $request): JsonResponse
    {
        $data = $request->validate([
            'phone' => ['required', 'string', 'max:32'],
            'password' => ['required', 'string', 'max:255'],
            'license_key' => ['required', 'string', 'min:4', 'max:256'],
            'device_id' => ['required', 'string', 'max:128'],
        ]);

        $salt = (string) config('licensing.key_salt');
        if ($salt === '') {
            return response()->json(['message' => 'Server license salt not configured'], 500);
        }

        $phone = AppAccount::normalizePhone($data['phone']);
        if (strlen($phone) < 8) {
            return response()->json(['message' => 'Invalid credentials'], 401);
        }

        /** @var AppAccount|null $account */
        $account = AppAccount::query()->where('phone', $phone)->first();
        if ($account === null || ! $account->verifyPassword($data['password'])) {
            return response()->json(['message' => 'Invalid credentials'], 401);
        }

        $normalized = strtoupper(trim($data['license_key']));
        $keyHash = hash('sha256', $normalized.$salt);

        /** @var License|null $license */
        $license = License::query()->where('key_hash', $keyHash)->first();
        if ($license === null) {
            return response()->json(['message' => 'Invalid license key'], 404);
        }
        if ($license->app_account_id === null || (int) $license->app_account_id !== (int) $account->id) {
            return response()->json(['message' => 'License key does not match this account'], 403);
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
}
