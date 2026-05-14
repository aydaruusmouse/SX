<?php

namespace App\Services;

use App\Models\AppAccount;
use App\Models\License;
use Firebase\JWT\ExpiredException;
use Firebase\JWT\JWT;
use Firebase\JWT\Key;
use Firebase\JWT\SignatureInvalidException;
use Illuminate\Support\Str;
use UnexpectedValueException;

class LicenseJwtService
{
    public function issue(License $license, string $deviceId, ?AppAccount $account = null): string
    {
        $cfg = config('licensing.jwt');
        $now = time();
        $ttl = max(60, (int) $cfg['ttl_seconds']);

        $private = file_get_contents(config('licensing.private_key_path'));
        if ($private === false || $private === '') {
            throw new \RuntimeException('License private key missing. Generate storage/keys/private.pem');
        }

        $payload = [
            'iss' => $cfg['issuer'],
            'aud' => $cfg['audience'],
            'iat' => $now,
            'nbf' => $now,
            'exp' => $now + $ttl,
            'jti' => (string) Str::uuid(),
            'sub' => (string) $license->id,
            'lid' => (string) $license->id,
            'did' => $deviceId,
        ];

        if ($account !== null) {
            $payload['aid'] = (string) $account->id;
            $payload['phone'] = $account->phone;
        }

        return JWT::encode($payload, $private, 'RS256');
    }

    /**
     * @return object{sub:string,lid:string,did:string,exp:int,iat:int,jti:string}
     */
    public function decode(string $jwt): object
    {
        $public = file_get_contents(config('licensing.public_key_path'));
        if ($public === false || $public === '') {
            throw new \RuntimeException('License public key missing.');
        }

        $cfg = config('licensing.jwt');

        try {
            $decoded = JWT::decode($jwt, new Key($public, 'RS256'));
        } catch (ExpiredException|SignatureInvalidException|UnexpectedValueException $e) {
            throw new \InvalidArgumentException('Invalid token', 0, $e);
        }

        if (($decoded->iss ?? null) !== $cfg['issuer']) {
            throw new \InvalidArgumentException('Invalid issuer');
        }
        if (($decoded->aud ?? null) !== $cfg['audience']) {
            throw new \InvalidArgumentException('Invalid audience');
        }

        return $decoded;
    }
}
