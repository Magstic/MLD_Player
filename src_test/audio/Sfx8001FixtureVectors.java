package audio;

import java.security.MessageDigest;
import java.util.Base64;

/** Real supplied PoN sampled-resource vectors, stored as compressed payload only. */
final class Sfx8001FixtureVectors {
    private Sfx8001FixtureVectors() {}

    static void audit() {
        verify("PoN_17.mld",
                "////HxEiIjMzIyMiIiIiISIhIdEvMjMzMzQzQzNDMzNDM0MzMzM0MzMzMyQ0M0IzM4j94e+t7u/O2k1nKu8hxfIuUR+BKP7vv+Hv"
                + "/er93a3dzt3K7NyszM3sytvdrfvL7Oq87azNHbvr3/t7d9rVMaVb/US71DHkGi4zwe0iNK/fMyQa4fNv76OIjLu7y8u7zLzLu7zL"
                + "vMu7vLy8u7zLvLvLy8u7vHcS//9EJBExRCSI4f/v2u3+vcvc7pzd3d/c3v3NfOf/8WHxPvE18op5Lfy50/+92//83O3v7s7R3+18"
                + "F0r+Q5MULiQe8TERHxExH/NPMok4//7NIeHO7BJ3OB4/pdLxQh7+HyLxwUH/9Cwyj8glG7E8s+LOLn6X3uNEz/4SI/+II9/R/P7h"
                + "3/3+d+oxLdXh8TLxMS8RIfMz4VRinYX4E/3O79Pu7v13zfEhEx3xIvMS0kMyXSsiUR7y5DMvE6RFIvJtTiJP8SLzJfHGUu6IqLq7"
                + "u7zLy8u7ZzESIjMzMyMzNEMzNEMlMzRDQ4PY/f6+vN39vLu8zr3b293Nzd3d7NvNva7s69vK7XfRLy0kHMNi0yEtQuHjMTM/ETOD"
                + "yPT93Uve37sSdzksQrPhPzT/z4j0z8Ec8+y8L8/e+uHu3v7P3+19F+vzMcEtITLtiKS6u7u7vMvLy2cyIjIzNDMzQzRDM0RTIjMj"
                + "IyIhEiIhITIyQiJBiuj//s3r3t6s7Xcs/i7m8u9CIvHxIxISMUISFEIzMkIzJDJDIzNDiejjHc0t38/L4vy9Ldvh28HezSx3v/IR"
                + "E/sRb9H98hTBEU8xG+Pzj7inqru7y7u8vMu7vMu7vLu8y8t3If8fRCQjMUOUuO3u3rvNzb3LfMfj8VEhEhIkI4oq7N6s3N7e3N13"
                + "Wi1P9RMRMhHCmLfuvfve7c39fYfVEUQ84kLkIy8iEfHy8xIhMSM/MiMiMxIlEvO45x/uL/7x/REe7x7h8d8RHv/+4fHv4fH/////"
                + "//////////////8A",
                6096,
                "71385d5d57a27d3c5cdb96f5913759fe6a56fe63cf29d99d4a242b11695c6c88");
        verify("PoN_18.mld",
                "////////cUVENDMjIyIiIyIiIiIiISISIiEiIiEhIhIiIRIiEhIiIRIiISESIhESEiKBuPL+z/rvHsv8z8HL/fy9zXf9/x/T/x5f"
                + "/u8fI+H/PyLy/yIiIf8yIRLkIiExHzIu0+QSXh3yEv8TL7WCmObv/ysf/r//HrPt7j3t/evC774R2v7czSHrr//t0uzOwtLr7Uy9"
                + "7n5nRSTJzLu7vMzLzMvMzMu8zLzMy7zMy7zMvMy8zLzcy8vMvMzN2czdLXf///Ezv/geJhzvX+Py8UQiEiI0MjIxIhKTuLb/rR3d"
                + "7svf7d783d++3zx328UfwUzt8a7T/v4b3tHf/Xf9ryPv/BkS377v8f78/t2zd9sVnzEsH/vR4tHt7/He7e937h8vEsjWzMzMzMzM"
                + "zMy8zHTF2939dsHt7e7t3d3czdzccRfx6O9yLtEa7d7e3Vc+IRMkMzIjIyMiyLLB3+397e7u3nYa0d/R/f3t3t1/5xvhHt3u797e"
                + "fqdDSB3+3+/v7v13Ox8o0dHf/+7OZxjSVPjx4d7u4X7GGnIeKR7hzu9hp1NYhBYpHv7e70ctLzMxEcjj3950+//+3v43GB/vftIa"
                + "4dHtf+Io3xGHdCjP4X2D1zocRU9PQiS0GB393v+n0//u/vcpHf7uxoTT8e503jge/284heLvf4HXGv5+Lu+D4TEXEbEoH6beL7fv"
                + "Jun+F/n1k9/2k3LT6HPTaum1URs1gxVaolb8/xE/snIzQhERKekd7+9Hrd3dEvc++f3+dV6t7t5yXyvq/X7UQ5zuTt/HEes81/Nh"
                + "rO7mJxQb+WWs3la89EwZ8y99bK5xbExuG1tSbeVhLtX02dZt/dWSFC8RIvdhRDIjqca5/tw0463c3nE0ktrebdpcn95vlRyn204x"
                + "+uW+/zeSRqPslV/+Wr1FaUTbphLyruQTbkSkfVMTyTwuyt2V/UeRzUT5lE/qL6M2aqJuNR3yL1rZRkZF/4SVvNx9TG3J3G5/8rHL"
                + "bPYZJtodoaaltPvnJFFtyeXG1RSxcv/UxVXraz4SlGQdX13+wXz1T25G67nmE6hikvHlni0rUhHmaSHGar4ib9wx8WUV7mZFk9pW"
                + "krs7qrRLuzuWUjqk2yIe8i+6VkVVRJKiu7u7XG+5zMxs1q/L3DzfJ7nc/beitcvs9fZeuexmrOeS3BNM8ia5f01EgbRMb7leQij1"
                + "r2vV4b4nGu03tKLj1a8qJm3y9tmy9Wq+shXfL+4k3zczQ7NFgcy7O5Q8yrscIUukvP1G4j/b3KVtRJHdRTnLTtkf86Zdom5ULjs/"
                + "SbpuxEYhlE1akl4+OuQfSm7UT1bxFlpFmlu98ayzKx1kpF8qVrY6P+KvK/xWVuX61pG7678j9rnb1lPMoqvkpR4luXLxbtWR/mtF"
                + "SfZaLqrjvRFupFLWVCYp7fmkqhFWzBGrVK3eVaSy/2u0K21tPir7JlJu9Nav5SNJL/bi1f5dvGUiViXNj1uMulT0GN66sckSRptB"
                + "yUSsviuP0e1rTTVsTB5OmSNOXeWv7vUTyUVd1NWvkTI5EvYx3v9drdVlRCTM78e7y1Q0/aq7srpuk7pRujw6uy3BS/KlSzT6PR76"
                + "m9JFbdO1XZIhKRHtGe4SblYhRUQUuFyru7u0TO/Jy1RKRZq7EfKtwsp9RKRdqky6Ky/PSpTcNkT6HsqTvNJFkiTJbaIs/aEtWkpF"
                + "9Un/xJ7LFF9NtapUNG35ncLKFeG1bKriVeMezm2iW1RFGzkpmqpiH9SVqv5qTf+rbB1eXJU5XuQxGfoWqeXv5a2h4WUe5RJvkVvO"
                + "5NbqKu8R5U4yN0QtRTuKqm6sQr/ILbH+Zqw6RW2jvhm7Qzp97cPvEhfv/21VrjRFJKnsr6q6pG1FurozSrqju/ITpkPKbVQvHqI8"
                + "yl1ENEqiLcrL4x9KoqxTVPIv2O/LbEOvXrw8yzxK/SqlTSE1zfIqbKzRVdMW7WqiPKElPTkaHcFuI8ZGVRxJLpOqUSFeLaruayVf"
                + "oVwtquUqOm+j4T8TFipfVUytH+WpT/1TvJ79Y6Te01qk+lzD//raJTpVxLWfwZw6EvZitBpcrbX5zDbt0by1kVNVtO/1vqkaRqw1"
                + "qsqf/6GyyuVqNF2pwx+q9LFeVKPBNOuoKm2SUp0a9Tnd++i+EjZMneZTrFEc4dfvHWJD/21MtLlsE1wanDuq9N+1KpTCVeHcH16R"
                + "W8VE9Sk5+pO5H9PE/5pDPC1Vk69OTJopTuwvzvn0X+RU7c7F+rTf9eKbH33yROVTJSmf2qSp//7JH6tTVZ9TpL+pHLEqXqxKRfoV"
                + "zxmq4r8025Re9KLO/u3ZNW5ENJyf86m6PjTvr7ttxUX0uUsrlNuiHMXxyvXqL0ai0rE1qytu47/fOtXaNW3qvqGSE9/F9EHORcKm"
                + "1VOTKx7EpBn6Tjzu6tRFTV72TqIkQhpWHd/1EY1PVeRVxd2pS5/6XZ8qHttF/ulWv5JTzpHsMtHt81vlVV708dqSISpk5P/d74wd"
                + "VUVEs+msu8s978a73B3W873c7fQ2o8vdVa1MvdzjLSq2y29cvfO9TV8bUqJL1VKiK/zetSzt1sXRzcYR0zWz/zUtniwlrE8c6q8R"
                + "PWPO5VxO1R3hH277XlVE4981qrvbRaGyy8ujPE+6zDNK5LHL/+HdJbpcM8Ohoiuq+/Q+GaPar9Xh/7v10TQzkdE0r8w8X6E8uzzl"
                + "O0ui+84h4x7a1URORK/ipD+rHFEe/rE61VxUNBrrHaq64tRForpS/6Mbqu1bHSqjTB9e8/4aTfQ5RPEW2hGsXM6iVLQrHiPBKxtF"
                + "1ET81K8bo9Eh5eofXkTcr0Xkut/TocqfXdM1u/MbOqGhwR7i/TFeVE5U5BOqLarJ06/8wtwiFlxFzEPu8cyywu5TxDJdTcQb/CMx"
                + "G/tD9OPv47/74kQh5Osurz3crjT9Ge7Un08fNeMk3ju8G78zHb3q/c9NQ+1FRP3L8cHMQs9PI8zuHCvbsk0+IbITKz9N/u8f9TFu"
                + "RES91OK6u/7x1L7MXTQ/1Mosu17csTyzLf/jHB8sNOLPRPLfTuTSTdQs7iUsHe730fH09CElQRHyNM3kPD6//vEfT+5LNDPjQf7L"
                + "3f3UTcLM7fL+G8zdRCP/stzy3F/+u169PU/CPvEt9Cw9JO3fEuO+4vRTTMRd48Q//B4s8f7xM/E+IiKxPSPLzC0s3MLdPfHDIdz9"
                + "Lv7+sewkTTIT2rLzytzCEu7//////////////////////////////////////////////w==",
                19616,
                "57949b06b13c2b4fd9d56300e1ceca328805710ac137ce727b55cd21311cc04d");
        verify("PoN_19.mld",
                "/1SIh4YSe67Fxk+ibRnh9VWCwjGx8n/i+ir878cubN01q5XSNCG2HEpyK+xTnr2iHX/77xRv2d4+7nz+5Kbu8jKRVOxbgS4s0/72"
                + "PnrrHUE9PBH1x7yzT81eO+KGHj3i3ubRfdscLyFNFnQzqq7PK1vFzefzXTMYHRpvITWT4U1+7P1hxurLo9F/7Exbz2STPp4e9SNG"
                + "6d9Bt8LvRpzMHKsW/Hse1P7OX1G10hO2H2Wq/hpOzBPTb73ETTFDPDzVtG+Vq+6eFypeLaXhvTJfwcEUwx9G6K7Eo+TEx8EdMtbS"
                + "M+NzgS36PyrhYhz742PuWx/8x7m+L8EzXsLtPRPVsfYcdKnbvMQ9pPdK2VFeRBpv1B5znM6fP+/d51+f498ibyv8Qr1e1sgqzEL5"
                + "Ms/HW+lCUTHrUS/7YqnavHv8obcsFsmV/FFBf9EuHFRIKl3kuZ6WH3odOm7dYcJZ2f0y0Sa8xF3OhcJWoxk4uZEVnTHkzxfv/T1O"
                + "UklL8sJutJyuovUbMT6lwp0icf+/I+4eIhIR//8=",
                3232,
                "19d3c6985ddf3c69c9663b9117c69e2bd47ec922d848406201527cce11022e18");
    }

    private static void verify(String name, String base64, int frames, String expectedSha256) {
        try {
            byte[] encoded = Base64.getDecoder().decode(base64);
            DecodedSampledResource decoded = Mfi8001Decoder.decode(encoded, 8000, 4, 1);
            if (decoded.getFrameCount() != frames) {
                throw new AssertionError(name + " frame count: expected " + frames
                        + ", got " + decoded.getFrameCount());
            }
            byte[] bytes = int32Le(decoded.copyInterleavedStereo());
            String actual = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!expectedSha256.equals(actual)) {
                throw new AssertionError(name + " decoded-resource SHA-256: expected "
                        + expectedSha256 + ", got " + actual);
            }
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AssertionError(name + ": " + ex, ex);
        }
    }

    private static byte[] int32Le(int[] samples) {
        byte[] bytes = new byte[samples.length * 4];
        for (int i = 0; i < samples.length; i++) {
            int value = samples[i];
            int offset = i * 4;
            bytes[offset] = (byte)value;
            bytes[offset + 1] = (byte)(value >>> 8);
            bytes[offset + 2] = (byte)(value >>> 16);
            bytes[offset + 3] = (byte)(value >>> 24);
        }
        return bytes;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(String.format("%02x", value & 0xFF));
        }
        return out.toString();
    }
}
