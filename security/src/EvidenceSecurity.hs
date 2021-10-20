-- Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

module Main (main) where

import Control.Monad (when)
import Data.List (intercalate, (\\), sortOn)
import Data.List.Extra (trim,groupOn)
import Data.List.Split (splitOn)
import Data.Map (Map)
import System.Exit (exitWith,ExitCode(ExitSuccess,ExitFailure))
import System.FilePath ((</>),splitPath)
import System.IO.Extra (hPutStrLn,stderr)
import Text.Read (readMaybe)
import qualified Bazel.Runfiles (create,rlocation)
import qualified Data.Map as Map (fromList,toList)

main :: IO ()
main = do
  rawLines <- getRawGitGrepOutput
  let parsed = map parseLine rawLines
  let lines = [ line | Right line <- parsed ]
  let cats = [ cat | Line{cat} <- lines ]
  let missingCats = [minBound ..maxBound] \\ cats
  let errs = [ err | Left err <- parsed ] ++ map NoTestsForCatagory missingCats
  let n_errs = length errs
  when (n_errs >= 1) $ do
    hPutStrLn stderr "** Errors while Evidencing Security; exiting with non-zero exit code."
    sequence_ [hPutStrLn stderr $ "** (" ++ show i ++ ") " ++ show err | (i,err) <- zip [1::Int ..] errs]
  print (collateLines lines)
  exitWith (if n_errs == 0 then ExitSuccess else ExitFailure n_errs)

data Catagory = Authorization | Privacy | Semantics | Performance
  deriving (Eq,Ord,Bounded,Enum)

data Description = Description
  { filename:: FilePath
  , lineno:: Int
  , freeText:: String
  }

data Line = Line { cat :: Catagory, desc :: Description }

newtype Collated = Collated (Map Catagory [Description])

data Err
  = FailedToSplitLineOn4colons String
  | FailedToParseLinenumFrom String
  | UnknownCatagoryInLine String String
  | NoTestsForCatagory Catagory
  deriving Show

getRawGitGrepOutput :: IO [String]
getRawGitGrepOutput = do
  -- NICK: document what is going on here, and the magic comment format
  -- NICK: do the git grep or equiavlent in haskell?
  -- NICK: need a bazel rule to generate this: git grep --line-number SECURITY__TEST > security/git-grep-raw.output
  path <- locate "git-grep-raw.output"
  s <- readFile path
  pure $ lines s
    where
      locate :: String -> IO FilePath
      locate name = do
        runfiles <- Bazel.Runfiles.create
        pure $ Bazel.Runfiles.rlocation runfiles ("com_github_digital_asset_daml/security" </> name)

parseLine :: String -> Either Err Line
parseLine string = do
  let sep = ":"
  case splitOn sep string of
    filename : linenoString : _magicComment_ : tag : rest@(_:_) -> do
      case catagoryFromTag (trim tag) of
        Nothing -> Left (UnknownCatagoryInLine (trim tag) string)
        Just cat -> do
          case readMaybe @Int linenoString of
            Nothing -> Left (FailedToParseLinenumFrom linenoString)
            Just lineno -> do
              let freeText = trim (intercalate sep rest)
              let desc = Description {filename,lineno,freeText}
              Right (Line {cat,desc})
    _ ->
      Left (FailedToSplitLineOn4colons string)

catagoryFromTag :: String -> Maybe Catagory
catagoryFromTag = \case
  "Authorization" -> Just Authorization
  "Privacy" -> Just Privacy
  "Semantics" -> Just Semantics
  "Performance" -> Just Performance
  _ -> Nothing

collateLines :: [Line] -> Collated
collateLines lines =
  Collated $ Map.fromList
  [ (cat, [ desc | Line{desc} <- group ])
  | group@(Line{cat}:_) <- groupOn (\Line{cat} -> cat) lines
  ]

instance Show Collated where
  show (Collated m) =
    unlines (["# Security tests, by catagory",""] ++
             [ unlines (("## " ++ show cat ++ ":") : map show (sortOn freeText descs))
             | (cat,descs) <- sortOn fst (Map.toList m)
             ])

instance Show Description where
  show Description{filename,lineno,freeText} =
    "- " ++ freeText ++  ": [" ++ basename filename ++ "](" ++ filename ++ "#L" ++ show lineno ++ ")"


basename :: FilePath -> FilePath
basename p = case reverse (splitPath p) of
  [] -> p
  x:_ -> x

instance Show Catagory where
  show = \case
    Authorization -> "Authorization"
    Privacy -> "Privacy"
    Semantics -> "Semantics"
    Performance -> "Performance"
