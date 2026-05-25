package scratch;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class FindStrings {
    public static void main(String[] args) {
        try {
            File dexFile = new File("classes.dex");
            if (!dexFile.exists()) {
                System.out.println("classes.dex not found!");
                return;
            }

            byte[] bytes = Files.readAllBytes(dexFile.toPath());
            System.out.println("Loaded " + bytes.length + " bytes from classes.dex");

            List<String> strings = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            
            for (byte b : bytes) {
                if (b >= 32 && b <= 126) {
                    sb.append((char) b);
                } else {
                    if (sb.length() >= 4) {
                        strings.add(sb.toString());
                    }
                    sb.setLength(0);
                }
            }
            if (sb.length() >= 4) {
                strings.add(sb.toString());
            }

            System.out.println("Total raw strings found: " + strings.size());
            
            System.out.println("\n--- All Strings (5 to 35 chars) ---");
            for (String s : strings) {
                if (s.length() >= 5 && s.length() <= 35) {
                    System.out.println(s);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
