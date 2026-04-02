package main;

public final class PlayerLauncher {
    private PlayerLauncher() {
    }

    public static void main(String[] args) {
        String[] launchArgs = args == null ? new String[0] : args.clone();
        if (shouldLaunchCli(launchArgs)) {
            try {
                Cli.main(launchArgs);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return;
        }

        SwingPlayer.main(stripGuiFlag(launchArgs));
    }

    private static boolean shouldLaunchCli(String[] args) {
        return args != null && args.length > 0 && !"--gui".equalsIgnoreCase(args[0]);
    }

    private static String[] stripGuiFlag(String[] args) {
        if (args == null || args.length == 0) {
            return new String[0];
        }
        if (!"--gui".equalsIgnoreCase(args[0])) {
            return args.clone();
        }
        if (args.length == 1) {
            return new String[0];
        }
        String[] stripped = new String[args.length - 1];
        System.arraycopy(args, 1, stripped, 0, stripped.length);
        return stripped;
    }
}
