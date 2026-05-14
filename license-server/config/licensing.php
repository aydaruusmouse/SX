<?php

return [

    'private_key_path' => env('LICENSE_PRIVATE_KEY_PATH', storage_path('keys/private.pem')),

    'public_key_path' => env('LICENSE_PUBLIC_KEY_PATH', storage_path('keys/public.pem')),

    /** Salt appended when hashing license keys (set in .env). */
    'key_salt' => env('LICENSE_KEY_SALT', ''),

    'jwt' => [
        'issuer' => env('LICENSE_JWT_ISS', env('APP_URL', 'http://localhost')),
        'audience' => env('LICENSE_JWT_AUD', 'sarif-auto'),
        'ttl_seconds' => (int) env('LICENSE_JWT_TTL', 60 * 60 * 24 * 30),
    ],

];
