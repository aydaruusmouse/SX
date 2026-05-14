<?php

namespace App\Services;

use App\Models\AppAccount;
use App\Models\License;
use Illuminate\Support\Str;

class AppCustomerProvisioner
{
    /**
     * @return array{account: AppAccount, plain_license_key: string}
     */
    public function create(
        string $phoneRaw,
        string $password,
        ?string $label = null,
        int $maxActivations = 1,
        ?\DateTimeInterface $expiresAt = null,
    ): array {
        $salt = (string) config('licensing.key_salt');
        if ($salt === '') {
            throw new \RuntimeException('LICENSE_KEY_SALT is not configured.');
        }

        $phone = AppAccount::normalizePhone($phoneRaw);
        if (strlen($phone) < 8 || strlen($phone) > 15) {
            throw new \InvalidArgumentException('Phone must be 8–15 digits.');
        }
        if (strlen($password) < 6) {
            throw new \InvalidArgumentException('Password must be at least 6 characters.');
        }
        if (AppAccount::query()->where('phone', $phone)->exists()) {
            throw new \InvalidArgumentException('An account with this phone already exists.');
        }

        $account = AppAccount::query()->create([
            'phone' => $phone,
            'password' => $password,
            'name' => null,
        ]);

        $segments = [];
        for ($i = 0; $i < 4; $i++) {
            $segments[] = strtoupper(Str::random(4));
        }
        $plainKey = 'SARIF-'.implode('-', $segments);
        $keyHash = hash('sha256', strtoupper($plainKey).$salt);

        License::query()->create([
            'app_account_id' => $account->id,
            'key_hash' => $keyHash,
            'label' => $label ?: 'Customer '.$phone,
            'max_activations' => max(1, $maxActivations),
            'expires_at' => $expiresAt,
            'revoked_at' => null,
        ]);

        return [
            'account' => $account,
            'plain_license_key' => $plainKey,
        ];
    }
}
