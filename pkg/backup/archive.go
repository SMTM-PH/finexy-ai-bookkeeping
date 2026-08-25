package backup

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	Format             = "ai-bookkeeping-full-backup"
	Version            = 1
	ManifestName       = "manifest.json"
	DatabaseName       = "database.sqlite3"
	StorageDirectory   = "storage"
	PendingFileSuffix  = ".restore-pending.zip"
	SQLiteHeaderLength = 16
)

var sqliteHeader = []byte("SQLite format 3\x00")

type Manifest struct {
	Format      string `json:"format"`
	Version     int    `json:"version"`
	GeneratedAt string `json:"generatedAt"`
}

func WriteArchive(writer io.Writer, databasePath string, storagePath string, generatedAt time.Time) error {
	archive := zip.NewWriter(writer)
	manifestData, err := json.MarshalIndent(Manifest{
		Format:      Format,
		Version:     Version,
		GeneratedAt: generatedAt.UTC().Format(time.RFC3339),
	}, "", "  ")
	if err != nil {
		return err
	}
	if err = writeBytes(archive, ManifestName, manifestData); err != nil {
		return err
	}
	if err = writeFile(archive, DatabaseName, databasePath); err != nil {
		return err
	}
	if err = writeBytes(archive, StorageDirectory+"/", nil); err != nil {
		return err
	}

	if storagePath != "" {
		if info, statErr := os.Stat(storagePath); statErr == nil && info.IsDir() {
			err = filepath.Walk(storagePath, func(path string, info os.FileInfo, walkErr error) error {
				if walkErr != nil {
					return walkErr
				}
				if path == storagePath {
					return nil
				}
				relative, relErr := filepath.Rel(storagePath, path)
				if relErr != nil {
					return relErr
				}
				name := filepath.ToSlash(filepath.Join(StorageDirectory, relative))
				if info.IsDir() {
					return writeBytes(archive, name+"/", nil)
				}
				if !info.Mode().IsRegular() {
					return fmt.Errorf("unsupported storage entry %q", relative)
				}
				return writeFile(archive, name, path)
			})
			if err != nil {
				return err
			}
		} else if statErr != nil && !os.IsNotExist(statErr) {
			return statErr
		}
	}

	return archive.Close()
}

func ValidateArchive(path string, maxUncompressedSize int64) error {
	reader, err := zip.OpenReader(path)
	if err != nil {
		return err
	}
	defer reader.Close()

	var manifest *Manifest
	databaseFound := false
	seen := make(map[string]struct{})
	var total uint64
	for _, entry := range reader.File {
		name, safe := safeArchiveName(entry.Name)
		if !safe || entry.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("unsafe backup entry %q", entry.Name)
		}
		if _, exists := seen[name]; exists {
			return fmt.Errorf("duplicate backup entry %q", name)
		}
		seen[name] = struct{}{}
		total += entry.UncompressedSize64
		if maxUncompressedSize > 0 && total > uint64(maxUncompressedSize) {
			return errors.New("backup expands beyond the allowed size")
		}
		if name != ManifestName && name != DatabaseName && name != StorageDirectory && !strings.HasPrefix(name, StorageDirectory+"/") {
			return fmt.Errorf("unknown backup entry %q", name)
		}
		switch name {
		case ManifestName:
			data, readErr := readZipEntry(entry, 64*1024)
			if readErr != nil {
				return readErr
			}
			var value Manifest
			if jsonErr := json.Unmarshal(data, &value); jsonErr != nil {
				return jsonErr
			}
			manifest = &value
		case DatabaseName:
			data, readErr := readZipPrefix(entry, SQLiteHeaderLength)
			if readErr != nil {
				return readErr
			}
			if !bytes.Equal(data, sqliteHeader) {
				return errors.New("backup database is not a SQLite database")
			}
			databaseFound = true
		}
	}
	if manifest == nil || manifest.Format != Format || manifest.Version != Version {
		return errors.New("unsupported backup manifest")
	}
	if !databaseFound {
		return errors.New("backup database is missing")
	}
	return nil
}

func ExtractArchive(path string, destination string, maxUncompressedSize int64) error {
	if err := ValidateArchive(path, maxUncompressedSize); err != nil {
		return err
	}
	reader, err := zip.OpenReader(path)
	if err != nil {
		return err
	}
	defer reader.Close()
	destination, err = filepath.Abs(destination)
	if err != nil {
		return err
	}
	for _, entry := range reader.File {
		name, _ := safeArchiveName(entry.Name)
		target := filepath.Join(destination, filepath.FromSlash(name))
		if entry.FileInfo().IsDir() || strings.HasSuffix(entry.Name, "/") {
			if err = os.MkdirAll(target, 0700); err != nil {
				return err
			}
			continue
		}
		if err = os.MkdirAll(filepath.Dir(target), 0700); err != nil {
			return err
		}
		source, openErr := entry.Open()
		if openErr != nil {
			return openErr
		}
		targetFile, createErr := os.OpenFile(target, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
		if createErr != nil {
			source.Close()
			return createErr
		}
		_, copyErr := io.Copy(targetFile, source)
		closeErr := targetFile.Close()
		source.Close()
		if copyErr != nil {
			return copyErr
		}
		if closeErr != nil {
			return closeErr
		}
	}
	return nil
}

func writeBytes(archive *zip.Writer, name string, data []byte) error {
	header := &zip.FileHeader{Name: name, Method: zip.Deflate}
	header.SetMode(0600)
	writer, err := archive.CreateHeader(header)
	if err != nil {
		return err
	}
	_, err = writer.Write(data)
	return err
}

func writeFile(archive *zip.Writer, name string, path string) error {
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()
	header := &zip.FileHeader{Name: name, Method: zip.Deflate}
	header.SetMode(0600)
	writer, err := archive.CreateHeader(header)
	if err != nil {
		return err
	}
	_, err = io.Copy(writer, file)
	return err
}

func readZipEntry(entry *zip.File, size int64) ([]byte, error) {
	reader, err := entry.Open()
	if err != nil {
		return nil, err
	}
	defer reader.Close()
	data, err := io.ReadAll(io.LimitReader(reader, size+1))
	if err != nil {
		return nil, err
	}
	if int64(len(data)) > size {
		return nil, errors.New("backup entry exceeds expected size")
	}
	return data, nil
}

func readZipPrefix(entry *zip.File, size int64) ([]byte, error) {
	reader, err := entry.Open()
	if err != nil {
		return nil, err
	}
	defer reader.Close()
	data := make([]byte, size)
	if _, err = io.ReadFull(reader, data); err != nil {
		return nil, err
	}
	return data, nil
}

func safeArchiveName(name string) (string, bool) {
	cleaned := strings.TrimSuffix(filepath.ToSlash(name), "/")
	if cleaned == "" || strings.HasPrefix(cleaned, "/") || strings.Contains(cleaned, "\\") {
		return "", false
	}
	if filepath.IsAbs(name) || cleaned == ".." || strings.HasPrefix(cleaned, "../") || strings.Contains(cleaned, "/../") {
		return "", false
	}
	return cleaned, true
}
