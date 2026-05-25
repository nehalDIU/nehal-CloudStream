package scratch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class TestDecrypt {
    public static void main(String[] args) {
        try {
            HttpClient client = HttpClient.newHttpClient();

            // 1. Fetch Security Key
            System.out.println("Fetching security key...");
            HttpRequest keyRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.hlowb.com/v0.1/system/getSecurityKey/1?channel=IndiaA&clientType=1&lang=en-US"))
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                .GET()
                .build();
            
            HttpResponse<String> keyResponse = client.send(keyRequest, HttpResponse.BodyHandlers.ofString());
            System.out.println("Security Key response code: " + keyResponse.statusCode());
            System.out.println("Security Key body: " + keyResponse.body());

            // Extract security key data string
            String keyBody = keyResponse.body();
            String keyMarker = "\"data\":\"";
            int keyIdx = keyBody.indexOf(keyMarker);
            if (keyIdx == -1) {
                System.out.println("Could not find data in security key response");
                return;
            }
            String apiKeyB64 = keyBody.substring(keyIdx + keyMarker.length(), keyBody.indexOf("\"", keyIdx + keyMarker.length()));
            String cleanedKeyB64 = apiKeyB64.replaceAll("[^A-Za-z0-9+/=]", "");
            System.out.println("apiKeyB64: " + apiKeyB64 + " -> cleaned: " + cleanedKeyB64);

            // 2. Fetch Home Page Encrypted Data
            System.out.println("\nFetching home page...");
            HttpRequest homeRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.hlowb.com/film-api/v0.1/category/home?channel=IndiaA&clientType=1&clientType=1&lang=en-US&locationId=1001&mode=1&packageName=com.external.castle&page=1&size=17"))
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                .GET()
                .build();
            
            HttpResponse<String> homeResponse = client.send(homeRequest, HttpResponse.BodyHandlers.ofString());
            System.out.println("Home page response code: " + homeResponse.statusCode());
            
            String homeBody = homeResponse.body().trim();
            System.out.println("Home body preview: " + homeBody.substring(0, Math.min(200, homeBody.length())));
            
            String encryptedB64 = "";
            int dataIdx = homeBody.indexOf(keyMarker);
            if (dataIdx != -1) {
                encryptedB64 = homeBody.substring(dataIdx + keyMarker.length(), homeBody.indexOf("\"", dataIdx + keyMarker.length()));
            } else {
                // If it's a raw string, clean it up
                if (homeBody.startsWith("\"") && homeBody.endsWith("\"")) {
                    homeBody = homeBody.substring(1, homeBody.length() - 1);
                }
                encryptedB64 = homeBody.replaceAll("[^A-Za-z0-9+/=]", "");
            }
            System.out.println("Encrypted data length: " + encryptedB64.length());

            // 3. Try Decrypting with "default_suffix" or check if it fails
            String[] suffixes = {"default_suffix", "default", "castle", ""};
            for (String suffix : suffixes) {
                System.out.println("\nTrying decryption with suffix: \"" + suffix + "\"");
                try {
                    byte[] apiKeyBytes = Base64.getDecoder().decode(cleanedKeyB64);
                    byte[] suffixBytes = suffix.getBytes(StandardCharsets.US_ASCII);
                    byte[] keyMaterial = new byte[apiKeyBytes.length + suffixBytes.length];
                    System.arraycopy(apiKeyBytes, 0, keyMaterial, 0, apiKeyBytes.length);
                    System.arraycopy(suffixBytes, 0, keyMaterial, apiKeyBytes.length, suffixBytes.length);

                    byte[] aesKey = new byte[16];
                    if (keyMaterial.length < 16) {
                        System.arraycopy(keyMaterial, 0, aesKey, 0, keyMaterial.length);
                    } else {
                        System.arraycopy(keyMaterial, 0, aesKey, 0, 16);
                    }

                    byte[] encryptedData = Base64.getDecoder().decode(encryptedB64);

                    byte[][] ivs = {
                        aesKey,
                        new byte[16],
                        "0000000000000000".getBytes(StandardCharsets.US_ASCII),
                        "1234567890123456".getBytes(StandardCharsets.US_ASCII),
                        java.util.Arrays.copyOf(encryptedData, 16)
                    };

                    String[] ivNames = {
                        "Same as AES Key",
                        "All Zeros (16 bytes)",
                        "ASCII '0' x16",
                        "ASCII '1234567890123456'",
                        "First 16 bytes of Ciphertext"
                    };

                    for (int i = 0; i < ivs.length; i++) {
                        byte[] iv = ivs[i];
                        String ivName = ivNames[i];
                        try {
                            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                            SecretKeySpec secretKey = new SecretKeySpec(aesKey, "AES");
                            IvParameterSpec ivSpec = new IvParameterSpec(iv);

                            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);
                            byte[] decrypted = cipher.doFinal(encryptedData);
                            String decryptedStr = new String(decrypted, StandardCharsets.UTF_8);

                            System.out.println("DECRYPTION SUCCESSFUL with IV: " + ivName + "!");
                            System.out.println("Decrypted prefix: " + decryptedStr.substring(0, Math.min(200, decryptedStr.length())));
                            return; // Stop if success
                        } catch (Exception e) {
                            // Suppress detailed output to keep it clean unless it succeeds
                        }
                    }
                    System.out.println("Failed with suffix \"" + suffix + "\" for all IV combinations.");
                } catch (Exception e) {
                    System.out.println("Failed with suffix \"" + suffix + "\": " + e.getMessage());
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
