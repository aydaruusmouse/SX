<?php

namespace Database\Seeders;

use App\Models\AppAccount;
use App\Models\License;
use Illuminate\Database\Seeder;

class LicenseSeeder extends Seeder
{
    public function run(): void
    {
        $salt = (string) config('licensing.key_salt');
        if ($salt === '') {
            $this->command?->warn('LICENSE_KEY_SALT is empty; skip license seed.');

            return;
        }

        $demoPhone = AppAccount::normalizePhone((string) env('DEMO_ACCOUNT_PHONE', '252634000111'));
        $demoPassword = (string) env('DEMO_ACCOUNT_PASSWORD', 'demo-secret-123');

        $account = AppAccount::query()->firstOrCreate(
            ['phone' => $demoPhone],
            ['password' => $demoPassword, 'name' => 'Demo user']
        );

        $plain = 'SARIF-DEMO-ACTIVATE';
        $keyHash = hash('sha256', strtoupper(trim($plain)).$salt);

        License::query()->updateOrCreate(
            ['key_hash' => $keyHash],
            [
                'app_account_id' => $account->id,
                'label' => 'Demo (dev)',
                'max_activations' => 3,
                'expires_at' => null,
                'revoked_at' => null,
            ]
        );

        $this->command?->info('Demo login: phone '.$demoPhone.', password from DEMO_ACCOUNT_PASSWORD, license SARIF-DEMO-ACTIVATE');
    }
}
