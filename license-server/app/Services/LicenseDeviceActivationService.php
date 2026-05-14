<?php

namespace App\Services;

use App\Models\License;
use App\Models\LicenseActivation;
use Illuminate\Http\JsonResponse;
use Illuminate\Support\Facades\DB;

class LicenseDeviceActivationService
{
    public function __construct(
        private LicenseJwtService $jwt,
    ) {}

    public function activateAndIssueToken(License $license, string $deviceId): string
    {
        return DB::transaction(function () use ($license, $deviceId) {
            $existing = LicenseActivation::query()
                ->where('license_id', $license->id)
                ->where('device_id', $deviceId)
                ->lockForUpdate()
                ->first();

            if ($existing === null) {
                $count = LicenseActivation::query()
                    ->where('license_id', $license->id)
                    ->lockForUpdate()
                    ->count();
                if ($count >= $license->max_activations) {
                    throw new \RuntimeException('ACTIVATION_LIMIT');
                }
                LicenseActivation::query()->create([
                    'license_id' => $license->id,
                    'device_id' => $deviceId,
                    'activated_at' => now(),
                ]);
            } else {
                $existing->update(['last_refreshed_at' => now()]);
            }

            $license->loadMissing('appAccount');

            return $this->jwt->issue($license, $deviceId, $license->appAccount);
        });
    }

    public function jsonActivationLimit(): JsonResponse
    {
        return response()->json(['message' => 'Activation limit reached'], 403);
    }
}
