/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara;

import static com.google.copybara.config.base.SkylarkUtil.convertFromNoneable;
import static com.google.copybara.config.base.SkylarkUtil.stringToEnum;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.copybara.authoring.Authoring;
import com.google.copybara.config.base.OptionsAwareModule;
import com.google.copybara.doc.annotations.Example;
import com.google.copybara.doc.annotations.UsesFlags;
import com.google.copybara.transform.ExplicitReversal;
import com.google.copybara.transform.Move;
import com.google.copybara.transform.Replace;
import com.google.copybara.transform.Sequence;
import com.google.copybara.transform.VerifyMatch;
import com.google.copybara.transform.metadata.MetadataSquashNotes;
import com.google.copybara.util.Glob;
import com.google.copybara.util.console.Console;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.skylarkinterface.SkylarkSignature;
import com.google.devtools.build.lib.syntax.BuiltinFunction;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.Runtime.NoneType;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkList.MutableList;
import com.google.devtools.build.lib.syntax.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Main configuration class for creating migrations.
 *
 * <p>This class is exposed in Skylark configuration as an instance variable
 * called "core". So users can use it as:
 * <pre>
 * core.workspace(
 *   name = "foo",
 *   ...
 * )
 * </pre>
 */
@SkylarkModule(
    name = Core.CORE_VAR,
    doc = "Core functionality for creating migrations, and basic transformations.",
    category = SkylarkModuleCategory.BUILTIN)
@UsesFlags(GeneralOptions.class)
public class Core implements OptionsAwareModule {

  public static final String CORE_VAR = "core";

  private final Map<String, Migration> migrations = new HashMap<>();
  private GeneralOptions generalOptions;
  private WorkflowOptions workflowOptions;

  @Override
  public void setOptions(Options options) {
    generalOptions = options.get(GeneralOptions.class);
    workflowOptions = options.get(WorkflowOptions.class);
  }

  public Map<String, Migration> getMigrations() {
    return migrations;
  }


  @SkylarkSignature(
      name = "glob",
      returnType = Glob.class,
      doc = "Glob returns a list of every file in the workdir that matches at least one"
          + " pattern in include and does not match any of the patterns in exclude.",
      parameters = {
          @Param(name = "include", type = SkylarkList.class,
              generic1 = String.class, doc = "The list of glob patterns to include"),
          @Param(name = "exclude", type = SkylarkList.class,
              generic1 = String.class, doc = "The list of glob patterns to exclude",
              defaultValue = "[]", named = true, positional = false),
      }, useLocation = true)
  public static final BuiltinFunction GLOB = new BuiltinFunction("glob") {
    public Glob invoke(SkylarkList include, SkylarkList exclude, Location location)
        throws EvalException {
      List<String> includeStrings = Type.STRING_LIST.convert(include, "include");
      List<String> excludeStrings = Type.STRING_LIST.convert(exclude, "exclude");
      try {
        return new Glob(includeStrings, excludeStrings);
      } catch (IllegalArgumentException e) {
        throw new EvalException(location, String.format(
                "Cannot create a glob from: include='%s' and exclude='%s': %s",
                includeStrings, excludeStrings, e.getMessage()), e);
      }
    }
  };

  @SkylarkSignature(name = "reverse", returnType = SkylarkList.class,
      doc = "Given a list of transformations, returns the list of transformations equivalent to"
          + " undoing all the transformations",
      parameters = {
          @Param(name = "self", type = Core.class, doc = "this object"),
          @Param(name = "transformations", type = SkylarkList.class,
              generic1 = Transformation.class, doc = "The transformations to reverse"),
      },
      objectType = Core.class, useLocation = true)
  public static final BuiltinFunction REVERSE =
      new BuiltinFunction("reverse") {
        public SkylarkList<Transformation> invoke(Core self, SkylarkList<Transformation> transforms,
            Location location)
            throws EvalException {

          ImmutableList.Builder<Transformation> builder = ImmutableList.builder();
          for (Transformation t : transforms.getContents(Transformation.class, "transformations")) {
            try {
              builder.add(t.reverse());
            } catch (NonReversibleValidationException e) {
              throw new EvalException(location, e.getMessage());
            }
          }

          return SkylarkList.createImmutable(builder.build().reverse());
        }
      };

  @SkylarkSignature(name = "workflow", returnType = NoneType.class,
      doc = "Defines a migration pipeline which can be invoked via the Copybara command.",
      parameters = {
          @Param(name = "self", type = Core.class, doc = "this object", positional = false),
          @Param(name = "name", type = String.class,
              doc = "The name of the workflow.", positional = false),
          @Param(name = "origin", type = Origin.class,
              doc = "Where to read the migration code from.", positional = false),
          @Param(name = "destination", type = Destination.class,
              doc = "Where to read the migration code from.", positional = false),
          @Param(name = "authoring", type = Authoring.class,
              doc = "The author mapping configuration from origin to destination.",
              positional = false),
          @Param(name = "transformations", type = SkylarkList.class,
              generic1 = Object.class,
              doc = "The transformations to be run for this workflow. They will run in sequence.",
              positional = false, defaultValue = "[]"),
          @Param(name = "exclude_in_origin", type = Glob.class,
              doc = "For compatibility purposes only. Use origin_files instead.",
              defaultValue = "N/A", positional = false, noneable = true),
          @Param(name = "exclude_in_destination", type = Glob.class,
              doc = "For compatibility purposes only. Use detination_files instead.",
              defaultValue = "N/A", positional = false, noneable = true),
          @Param(name = "origin_files", type = Glob.class,
              doc = "A glob relative to the workdir that will be read from the"
              + " origin during the import. For example glob([\"**.java\"]), all java files,"
              + " recursively, which excludes all other file types.",
              defaultValue = "glob(['**'])", positional = false, noneable = true),
          @Param(name = "destination_files", type = Glob.class,
              doc = "A glob relative to the root of the destination repository that matches"
              + " files that are part of the migration. Files NOT matching this glob will never"
              + " be removed, even if the file does not exist in the source. For example"
              + " glob(['**'], exclude = ['**/BUILD']) keeps all BUILD files in destination when"
              + " the origin does not have any BUILD files. You can also use this to limit the"
              + " migration to a subdirectory of the destination,"
              + " e.g. glob(['java/src/**'], exclude = ['**/BUILD']) to only affect non-BUILD files"
              + " in java/src.",
              defaultValue = "glob(['**'])", positional = false, noneable = true),
          @Param(name = "mode", type = String.class, doc = ""
              + "Workflow mode. Currently we support three modes:<br>"
              + "<ul>"
              + "<li><b>'SQUASH'</b>: Create a single commit in the destination with new tree"
              + " state.</li>"
              + "<li><b>'ITERATIVE'</b>: Import each origin change individually.</li>"
              + "<li><b>'CHANGE_REQUEST'</b>: Import an origin tree state diffed by a common parent"
              + " in destination. This could be a GH Pull Request, a Gerrit Change, etc.</li>"
              + "</ul>",
              defaultValue = "\"SQUASH\"", positional = false),
          @Param(name = "include_changelist_notes", type = Boolean.class,
              doc = "Include a list of change list messages that were imported."
                  + "**DEPRECATED**: This method is about to be removed.",
              defaultValue = "False", positional = false),
          @Param(name = "reversible_check", type = Boolean.class,
              doc = "Indicates if the tool should try to to reverse all the transformations"
                  + " at the end to check that they are reversible.<br/>The default value is"
                  + " True for 'CHANGE_REQUEST' mode. False otherwise",
              defaultValue = "True for 'CHANGE_REQUEST' mode. False otherwise",
              noneable = true, positional = false),
          @Param(name = "ask_for_confirmation", type = Boolean.class,
              doc = "Indicates that the tool should show the diff and require user's"
                  + " confirmation before making a change in the destination.",
              defaultValue = "False", positional = false),
      },
      objectType = Core.class, useLocation = true, useEnvironment = true)
  @UsesFlags({WorkflowOptions.class})
  public static final BuiltinFunction WORKFLOW = new BuiltinFunction("workflow",
      ImmutableList.of(
          MutableList.EMPTY,
          Runtime.NONE,
          Runtime.NONE,
          Runtime.NONE,
          Runtime.NONE,
          "SQUASH",
          false,
          Runtime.NONE,
          false,
          MutableList.EMPTY
      )) {
    // This converts a "FOO_files"/"exclude_in_FOO" pair of arguments to a single glob matcher.
    // Only one or neither argument in the pair can be specified.
    // TODO(copybara-team): Get all configurations using the positive specification method and
    // remove support for exclude_in_FOO specifiers.
    private Glob convertFileSpecifier(
        Location location, Object positiveSpecifier, Object excludeSpecifier)
        throws EvalException {
      if (!EvalUtils.isNullOrNone(excludeSpecifier)) {
        if (!EvalUtils.isNullOrNone(positiveSpecifier)) {
          throw new EvalException(location, "Do not use exclude_in_{destination|origin} in new"
              + " Copybara configs. Use only {destination|origin}_files.");
        }
        return new Glob(ImmutableList.of("**"), (Glob) excludeSpecifier);
      } else {
        return convertFromNoneable(positiveSpecifier, Glob.ALL_FILES);
      }
    }

    public NoneType invoke(Core self, String workflowName,
        Origin<Reference> origin, Destination<?> destination, Authoring authoring,
        SkylarkList<?> transformations,
        Object excludeInOrigin,
        Object excludeInDestination,
        Object originFiles,
        Object destinationFiles,
        String modeStr,
        Boolean includeChangelistNotes,
        Object reversibleCheckObj,
        Boolean askForConfirmation,
        Location location,
        Environment env)
        throws EvalException {
      WorkflowMode mode = stringToEnum(location, "mode", modeStr, WorkflowMode.class);
      Console console = self.generalOptions.console();

      // TODO(copybara-team): Remove this and the field once clients migrated.
      if (includeChangelistNotes && mode == WorkflowMode.SQUASH) {
        console.warn("'include_changelist_notes' field is deprecated. Use metadata.squash_notes()"
            + " transformation instead");
        MetadataSquashNotes squashNotes = new MetadataSquashNotes(
            "Imported change by Copybara.'.\n\n"
                + "This change was generated by Copybara.\n", 100,
            /*compact=*/true, /*showRef*/true, /*showAuthor*/true, /*oldestFirst*/false);
        transformations = SkylarkList.createImmutable(Iterables.concat(
            ImmutableList.<Object>of(squashNotes),
            transformations.getImmutableList()
        ));
      }

      Sequence sequenceTransform = Sequence.fromConfig(transformations, "transformations", env);
      Transformation reverseTransform = null;
      if (!self.generalOptions.isDisableReversibleCheck() &&
              convertFromNoneable(reversibleCheckObj, mode == WorkflowMode.CHANGE_REQUEST)) {
        try {
          reverseTransform = sequenceTransform.reverse();
        } catch (NonReversibleValidationException e) {
          throw new EvalException(location, e.getMessage());
        }
      }

      if (!EvalUtils.isNullOrNone(excludeInOrigin)) {
        console.warn("core.workflow(exclude_in_origin) arg is deprecated, use"
            + " origin_files = glob(['**'], exclude = [exclude globs]) instead");
      }
      if (!EvalUtils.isNullOrNone(excludeInDestination)) {
        console.warn("core.workflow(exclude_in_destination) arg is deprecated, use"
            + " destination_files = glob(['**'], exclude = [exclude globs]) instead");
      }
      self.addMigration(location, workflowName, new Workflow<>(
          workflowName,
          origin,
          destination,
          authoring,
          sequenceTransform,
          self.workflowOptions.getLastRevision(),
          console,
          convertFileSpecifier(location, originFiles, excludeInOrigin),
          convertFileSpecifier(location, destinationFiles, excludeInDestination),
          mode,
          self.workflowOptions,
          reverseTransform,
          self.generalOptions.isVerbose(),
          askForConfirmation,
          self.generalOptions.isForced()));
      return Runtime.NONE;
    }
  };

  public void addMigration(Location location, String name, Migration migration)
      throws EvalException {
    if (migrations.put(name, migration) != null) {
      throw new EvalException(location,
          String.format("A migration with the name '%s' is already defined", name));
    }
  }

  @SuppressWarnings("unused")
  @SkylarkSignature(
      name = "move",
      returnType = Move.class,
      doc = "Moves files between directories and renames files",
      parameters = {
        @Param(name = "self", type = Core.class, doc = "this object"),
        @Param(name = "before", type = String.class, doc = ""
            + "The name of the file or directory before moving. If this is the empty"
            + " string and 'after' is a directory, then all files in the workdir will be moved to"
            + " the sub directory specified by 'after', maintaining the directory tree."),
        @Param(name = "after", type = String.class, doc = ""
            + "The name of the file or directory after moving. If this is the empty"
            + " string and 'before' is a directory, then all files in 'before' will be moved to"
            + " the repo root, maintaining the directory tree inside 'before'."),
          @Param(name = "paths", type = Glob.class,
              doc = "A glob expression relative to 'before' if it represents a directory."
                  + " Only files matching the expression will be moved. For example,"
                  + " glob([\"**.java\"]), matches all java files recursively inside"
                  + " 'before' folder. Defaults to match all the files recursively.",
              defaultValue = "glob([\"**\"])"),
          @Param(name = "overwrite",
              doc = "Overwrite destination files if they already exist. Note that this makes the"
                  + " transformation non-reversible, since there is no way to know if the file"
                  + " was overwritten or not in the reverse workflow.",
              type = Boolean.class, defaultValue = "False")
      },
      objectType = Core.class, useLocation = true)
  @Example(title = "Move a directory",
      before = "Move all the files in a directory to another directory:",
      code = "core.move(\"foo/bar_internal\", \"bar\")",
      after = "In this example, `foo/bar_internal/one` will be moved to `bar/one`.")
  @Example(title = "Move all the files to a subfolder",
      before = "Move all the files in the checkout dir into a directory called foo:",
      code = "core.move(\"\", \"foo\")",
      after = "In this example, `one` and `two/bar` will be moved to `foo/one` and `foo/two/bar`.")
  @Example(title = "Move a subfolder's content to the root",
      before = "Move the contents of a folder to the checkout root directory:",
      code = "core.move(\"foo\", \"\")",
      after = "In this example, `foo/bar` would be moved to `bar`.")
  public static final BuiltinFunction MOVE = new BuiltinFunction("move",
      ImmutableList.of(Glob.ALL_FILES, false)) {
    @SuppressWarnings("unused")
    public Move invoke(Core self, String before, String after, Glob paths, Boolean overwrite,
        Location location) throws EvalException {
      return Move.fromConfig(before, after, self.workflowOptions, paths, overwrite, location);
    }
  };

  @SkylarkSignature(
      name = "replace",
      returnType = Replace.class,
      doc = "Replace a text with another text using optional regex groups. This tranformer can be"
          + " automatically reversed.",
      parameters = {
          @Param(name = "self", type = Core.class, doc = "this object"),
          @Param(name = "before", type = String.class,
              doc = "The text before the transformation. Can contain references to regex groups."
              + " For example \"foo${x}text\".<p>If '$' literal character needs to be matched, "
              + "'`$$`' should be used. For example '`$$FOO`' would match the literal '$FOO'."),
          @Param(name = "after", type = String.class,
              doc = "The text after the transformation. It can also contain references to regex "
                  + "groups, like 'before' field."),
          @Param(name = "regex_groups", type = SkylarkDict.class,
              doc = "A set of named regexes that can be used to match part of the replaced text."
                  + " For example {\"x\": \"[A-Za-z]+\"}", defaultValue = "{}"),
          @Param(name = "paths", type = Glob.class,
              doc = "A glob expression relative to the workdir representing the files to apply"
                  + " the transformation. For example, glob([\"**.java\"]), matches all java files"
                  + " recursively. Defaults to match all the files recursively.",
              defaultValue = "glob([\"**\"])"),
          @Param(name = "first_only", type = Boolean.class,
              doc = "If true, only replaces the first instance rather than all. In single line"
              + " mode, replaces the first instance on each line. In multiline mode, replaces the"
              + " first instance in each file.",
              defaultValue = "False"),
          @Param(name = "multiline", type = Boolean.class,
              doc = "Whether to replace text that spans more than one line.",
              defaultValue = "False"),
          @Param(name = "repeated_groups", type = Boolean.class,
              doc = "Allow to use a group multiple times. For example foo${repeated}/${repeated}."
                  + " Note that this mechanism doesn't use backtracking. In other words, the group"
                  + " instances are treated as different groups in regex construction and then a"
                  + " validation is done after that.",
              defaultValue = "False"),
      },
      objectType = Core.class, useLocation = true)
  @Example(title = "Simple replacement",
      before = "Replaces the text \"internal\" with \"external\" in all java files",
      code = "core.replace(\n"
          + "    before = \"internal\",\n"
          + "    after = \"external\",\n"
          + "    paths = glob([\"**.java\"]),\n"
          + ")")
  @Example(title = "Replace using regex groups",
      before = "In this example we map some urls from the internal to the external version in"
          + " all the files of the project.",
      code = "core.replace(\n"
          + "        before = \"https://some_internal/url/${pkg}.html\",\n"
          + "        after = \"https://example.com/${pkg}.html\",\n"
          + "        regex_groups = {\n"
          + "            \"pkg\": \".*\",\n"
          + "        },\n"
          + "    )",
      after = "So a url like `https://some_internal/url/foo/bar.html` will be transformed to"
          + " `https://example.com/foo/bar.html`.")
  @Example(title = "Remove confidential blocks",
      before = "This example removes blocks of text/code that are confidential and thus shouldn't"
          + "be exported to a public repository.",
      code = "core.replace(\n"
          + "        before = \"${x}\",\n"
          + "        after = \"\",\n"
          + "        multiline = True,\n"
          + "        regex_groups = {\n"
          + "            \"x\": \"(?m)^.*BEGIN-INTERNAL[\\\\w\\\\W]*?END-INTERNAL.*$\\\\n\",\n"
          + "        },\n"
          + "    )",
      after = "This replace would transform a text file like:\n\n"
          + "```\n"
          + "This is\n"
          + "public\n"
          + " // BEGIN-INTERNAL\n"
          + " confidential\n"
          + " information\n"
          + " // END-INTERNAL\n"
          + "more public code\n"
          + " // BEGIN-INTERNAL\n"
          + " more confidential\n"
          + " information\n"
          + " // END-INTERNAL\n"
          + "```\n\n"
          + "Into:\n\n"
          + "```\n"
          + "This is\n"
          + "public\n"
          + "more public code\n"
          + "```\n\n")
  public static final BuiltinFunction REPLACE = new BuiltinFunction("replace",
      ImmutableList.of(
          SkylarkDict.empty(),
          Glob.ALL_FILES,
          false,
          false,
          false)) {
    public Replace invoke(Core self, String before, String after,
        SkylarkDict<String, String> regexes, Glob paths, Boolean firstOnly,
        Boolean multiline, Boolean repeatedGroups, Location location) throws EvalException {
      return Replace.create(location,
          before,
          after,
          Type.STRING_DICT.convert(regexes, "regex_groups"),
          paths,
          firstOnly,
          multiline,
          repeatedGroups,
          self.workflowOptions);
    }
  };

  @SkylarkSignature(
      name = "verify_match",
      returnType = VerifyMatch.class,
      doc = "Verifies that a RegEx matches (or not matches) the specified files. Does not, " +
          "transform anything, but will stop the workflow if it fails.",
      parameters = {
          @Param(name = "self", type = Core.class, doc = "this object"),
          @Param(name = "regex", type = String.class,
              doc = "The regex pattern to verify. To satisfy the validation, there has to be at"
                  + "least one (or no matches if verify_no_match) match in each of the files "
                  + "included in paths. The re2j pattern will be applied in multiline mode, i.e."
                  + " '^' refers to the beginning of a file and '$' to its end."),
          @Param(name = "paths", type = Glob.class,
              doc = "A glob expression relative to the workdir representing the files to apply"
              + " the transformation. For example, glob([\"**.java\"]), matches all java files"
              + " recursively. Defaults to match all the files recursively.",
              defaultValue = "glob([\"**\"])"),
          @Param(name = "verify_no_match", type = Boolean.class,
              doc = "If true, the transformation will verify that the RegEx does not match.",
              defaultValue = "False"),
      },
      objectType = Core.class, useLocation = true)
  public static final BuiltinFunction VERIFY_MATCH = new BuiltinFunction("verify_match",
      ImmutableList.of(
          Glob.ALL_FILES,
          false
      )) {
    public VerifyMatch invoke(Core self, String regex, Glob paths, Boolean verifyNoMatch,
        Location location) throws EvalException {
      return VerifyMatch.create(location,
          regex,
          paths,
          verifyNoMatch);
    }
  };

  @SkylarkSignature(
      name = "transform",
      returnType = Transformation.class,
      doc = "Creates a transformation with a particular, manually-specified, reversal, where the"
      + " forward version and reversed version of the transform are represented as lists of"
      + " transforms. The is useful if a transformation does not automatically reverse, or if the"
      + " automatic reversal does not work for some reason.",
      parameters = {
          @Param(name = "self", type = Core.class, doc = "this object"),
          @Param(name = "transformations",
              type = SkylarkList.class, generic1 = Transformation.class,
              doc = "The list of transformations to run as a result of running this"
              + " transformation."),
          @Param(name = "reversal", type = SkylarkList.class, generic1 = Transformation.class,
              doc = "The list of transformations to run as a result of running this"
              + " transformation in reverse.", named = true, positional = false),
      },
      objectType = Core.class, useEnvironment = true)
  public static final BuiltinFunction TRANSFORM = new BuiltinFunction("transform") {
    public Transformation invoke(Core self,
        SkylarkList<Transformation> transformations,
        SkylarkList<Transformation> reversal, Environment env) throws EvalException {
      return new ExplicitReversal(
          Sequence.fromConfig(transformations, "transformations", env),
          Sequence.fromConfig(reversal, "reversal", env));
    }
  };

  public static Core getCore(Environment env) {
    return (Core) env.getGlobals().get(CORE_VAR);
  }
}
