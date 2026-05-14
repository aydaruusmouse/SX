<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\HasMany;
use Illuminate\Support\Facades\Hash;

class AppAccount extends Model
{
    protected $fillable = [
        'phone',
        'password',
        'name',
    ];

    protected $hidden = [
        'password',
    ];

    protected function casts(): array
    {
        return [
            'password' => 'hashed',
        ];
    }

    public function licenses(): HasMany
    {
        return $this->hasMany(License::class, 'app_account_id');
    }

    /** Digits only, max 15 — must match Android [SecurePrefs.normalizePhoneDigits]. */
    public static function normalizePhone(string $raw): string
    {
        $d = preg_replace('/\D+/', '', $raw) ?? '';
        if (strlen($d) > 15) {
            return substr($d, -15);
        }

        return $d;
    }

    public function verifyPassword(string $plain): bool
    {
        return Hash::check($plain, $this->password);
    }
}
