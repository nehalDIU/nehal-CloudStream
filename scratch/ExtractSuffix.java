package scratch;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ExtractSuffix {

    public static void main(String[] args) {
        String apkPath = "scratch/castle.apk";
        System.out.println("Reading APK from: " + apkPath);

        Set<String> allStrings = new HashSet<>();
        try (ZipFile zipFile = new ZipFile(apkPath)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".dex")) {
                    System.out.println("Processing DEX entry: " + entry.getName() + " (" + entry.getSize() + " bytes)");
                    try (InputStream is = zipFile.getInputStream(entry)) {
                        byte[] dexBytes = readAllBytes(is);
                        List<String> dexStrings = parseDexStrings(dexBytes);
                        System.out.println("Found " + dexStrings.size() + " strings in " + entry.getName());
                        allStrings.addAll(dexStrings);
                    } catch (Exception e) {
                        System.out.println("Error parsing DEX entry " + entry.getName() + ": " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Error reading APK: " + e.getMessage());
            return;
        }

        System.out.println("\nTotal unique strings extracted from all DEX files: " + allStrings.size());

        // Let's filter and look for candidate suffix strings
        System.out.println("\n--- Candidate Suffix Strings ---");
        for (String s : allStrings) {
            // Suffixes are usually alphanumeric or contain special characters, 5-30 chars
            if (s.length() >= 5 && s.length() <= 35) {
                String lower = s.toLowerCase();
                if (lower.contains("key") || lower.contains("suffix") || lower.contains("hlowb") || 
                    lower.contains("decrypt") || lower.contains("aes") || lower.contains("castle")) {
                    System.out.println("Found match: " + s);
                }
            }
        }

        // Also output all strings of interest to a file for deeper analysis if needed
        try (java.io.PrintWriter out = new java.io.PrintWriter("scratch/apk_strings.txt")) {
            for (String s : allStrings) {
                out.println(s);
            }
            System.out.println("\nSaved all unique strings to scratch/apk_strings.txt");
        } catch (Exception e) {
            System.out.println("Error saving strings to file: " + e.getMessage());
        }
    }

    private static byte[] readAllBytes(InputStream is) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }

    private static List<String> parseDexStrings(byte[] dexBytes) throws Exception {
        List<String> strings = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(dexBytes);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Verify magic
        byte[] magic = new byte[8];
        buf.get(magic);
        String magicStr = new String(magic, StandardCharsets.US_ASCII);
        if (!magicStr.startsWith("dex\n")) {
            throw new Exception("Invalid DEX magic header");
        }

        buf.position(56);
        int stringIdsSize = buf.getInt();
        int stringIdsOff = buf.getInt();

        for (int i = 0; i < stringIdsSize; i++) {
            buf.position(stringIdsOff + i * 4);
            int stringDataOff = buf.getInt();

            buf.position(stringDataOff);
            // Read ULEB128 string length
            readUleb128(buf);

            // Read null-terminated UTF-8 string data
            ByteArrayOutputStream stringBytes = new ByteArrayOutputStream();
            while (true) {
                byte b = buf.get();
                if (b == 0) {
                    break;
                }
                stringBytes.write(b);
            }
            strings.add(new String(stringBytes.toByteArray(), StandardCharsets.UTF_8));
        }

        return strings;
    }

    private static int readUleb128(ByteBuffer buf) {
        int result = 0;
        int shift = 0;
        while (true) {
            byte b = buf.get();
            result |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        return result;
    }
}
