package com.github.mustachejava;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A pseudo interpreter / compiler. Instead of compiling to Java code, it compiles to a
 * list of instructions to execute.
 * <p/>
 * User: sam
 * Date: 5/14/11
 * Time: 3:52 PM
 */
public class MustacheParser {
  public static final String DEFAULT_SM = "{{";
  public static final String DEFAULT_EM = "}}";
  private MustacheFactory cf;

  public MustacheParser(MustacheFactory cf) {
    this.cf = cf;
  }

  public Mustache compile(String file) {
    Reader reader = cf.getReader(file);
    if (reader == null) {
      throw new MustacheException("Failed to find: " + file);
    }
    return compile(reader, file);
  }

  public Mustache compile(Reader reader, String file) {
    return compile(reader, file, DEFAULT_SM, DEFAULT_EM);
  }

  public Mustache compile(Reader reader, String file, String sm, String em) {
    return compile(reader, null, new AtomicInteger(0), file, sm, em);
  }

  public Mustache compile(final Reader reader, String tag, final AtomicInteger currentLine, String file, String sm, String em) throws MustacheException {
    if (reader == null) {
      throw new MustacheException("Reader is null");
    }
    Reader br;
    if (reader.markSupported()) {
      br = reader;
    } else {
      br = new BufferedReader(reader);
    }
    MustacheVisitor mv = cf.createMustacheVisitor();
    // Now we grab the mustache template
    boolean onlywhitespace = true;
    boolean iterable = currentLine.get() != 0;
    currentLine.compareAndSet(0, 1);
    StringBuilder out = new StringBuilder();
    try {
      int c;
      while ((c = br.read()) != -1) {
        if (c == '\r') {
          continue;
        }
        // Increment the line
        if (c == '\n') {
          currentLine.incrementAndGet();
          if (!iterable || (iterable && !onlywhitespace)) {
            out.append("\n");
          }
          out = write(mv, out, currentLine.intValue());

          iterable = false;
          onlywhitespace = true;
          continue;
        }
        // Check for a mustache start
        if (c == sm.charAt(0)) {
          br.mark(1);
          if (sm.length() == 1 || br.read() == sm.charAt(1)) {
            // Two mustaches, now capture command
            StringBuilder sb = new StringBuilder();
            while ((c = br.read()) != -1) {
              br.mark(1);
              if (c == em.charAt(0)) {
                if (em.length() > 1) {
                  if (br.read() == em.charAt(1)) {
                    // Matched end
                    break;
                  } else {
                    // Only one
                    br.reset();
                  }
                } else break;
              }
              sb.append((char) c);
            }
            final String command = cf.translate(sb.toString());
            final char ch = command.charAt(0);
            final String variable = command.substring(1).trim();
            switch (ch) {
              case '#':
              case '^':
              case '<':
              case '$': {
                int line = currentLine.get();
                final Mustache mustache = compile(br, variable, currentLine, file, sm, em);
                int lines = currentLine.get() - line;
                if (!onlywhitespace || lines == 0) {
                  write(mv, out, currentLine.intValue());
                }
                out = new StringBuilder();
                switch (ch) {
                  case '#':
                    mv.iterable(variable, mustache, file, line, sm, em);
                    break;
                  case '^':
                    mv.notIterable(variable, mustache, file, line, sm, em);
                    break;
                  case '<':
                    mv.extend(variable, mustache, file, line, sm, em);
                    break;
                  case '$':
                    mv.name(variable, mustache, file, line, sm, em);
                    break;
                }
                iterable = lines != 0;
                break;
              }
              case '/': {
                // Tag end
                if (!onlywhitespace) {
                  write(mv, out, currentLine.intValue());
                }
                if (!variable.equals(tag)) {
                  throw new MustacheException(
                          "Mismatched start/end tags: " + tag + " != " + variable + " in " + file + ":" + currentLine);
                }

                return mv.mustache(file, sm, em);
              }
              case '>': {
                out = write(mv, out, currentLine.intValue());
                mv.partial(variable, file, currentLine.get(), sm, em);
                break;
              }
              case '{': {
                out = write(mv, out, currentLine.intValue());
                // Not escaped
                String name = variable;
                if (em.charAt(1) != '}') {
                  name = variable.substring(0, variable.length() - 1);
                } else {
                  if (br.read() != '}') {
                    throw new MustacheException(
                            "Improperly closed variable in " + file + ":" + currentLine);
                  }
                }
                final String finalName = name;
                mv.value(finalName, false, currentLine.intValue(), sm, em);
                break;
              }
              case '&': {
                // Not escaped
                out = write(mv, out, currentLine.intValue());
                mv.value(variable, false, currentLine.intValue(), sm, em);
                break;
              }
              case '%':
                // Pragmas
                out = write(mv, out, currentLine.intValue());
                break;
              case '!':
                // Comment
                out = write(mv, out, currentLine.intValue());
                break;
              case '=':
                // Change delimiters
                out = write(mv, out, currentLine.intValue());
                String delimiters = command.replaceAll("\\s+", "");
                int length = delimiters.length();
                if (length > 6 || length / 2 * 2 != length) {
                  throw new MustacheException("Invalid delimiter string");
                }
                sm = delimiters.substring(1, length / 2);
                em = delimiters.substring(length / 2, length - 1);
                break;
              default: {
                if (c == -1) {
                  throw new MustacheException(
                          "Improperly closed variable in " + file + ":" + currentLine);
                }
                // Reference
                out = write(mv, out, currentLine.intValue());
                mv.value(command.trim(), true, currentLine.intValue(), sm, em);
                break;
              }
            }
            continue;
          } else {
            // Only one
            br.reset();
          }
        }
        onlywhitespace = onlywhitespace && (c == ' ' || c == '\t' || c == '\r');
        out.append((char) c);
      }
      write(mv, out, currentLine.intValue());
      br.close();
    } catch (IOException e) {
      throw new MustacheException("Failed to read", e);
    }
    mv.eof(currentLine.intValue());
    return mv.mustache(file, sm, em);
  }

  /**
   * Ignore empty strings and append to the previous code if it was also a write.
   */
  private StringBuilder write(MustacheVisitor mv, StringBuilder out, int line) {
    String text = out.toString();
    mv.write(text, line, null, null);
    return new StringBuilder();
  }

}
