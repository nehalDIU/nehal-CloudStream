async function main() {
    const headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    };
    
    console.log('--- TEST 1: GET instantcloud /download ---');
    try {
        const res = await fetch('https://instantcloud.org/file/MpYYcLuL/download', { headers, redirect: 'manual' });
        console.log('Status:', res.status);
        console.log('Headers location:', res.headers.get('location'));
        console.log('Content-Type:', res.headers.get('content-type'));
    } catch(e) {
        console.error(e);
    }
    
    console.log('\n--- TEST 2: GET with follow redirect ---');
    try {
        const res = await fetch('https://instantcloud.org/file/MpYYcLuL/download', { headers, redirect: 'follow' });
        console.log('Final URL:', res.url);
        console.log('Status:', res.status);
        console.log('Content-Type:', res.headers.get('content-type'));
        const text = await res.text();
        console.log('Body start:', text.substring(0, 300));
    } catch(e) {
        console.error(e);
    }
}

main();
