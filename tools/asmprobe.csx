#r "C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\BuildTools\\MSBuild\\Current\\Bin\\Roslyn\\System.Collections.Immutable.dll"
#r "C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\BuildTools\\MSBuild\\Current\\Bin\\Roslyn\\System.Reflection.Metadata.dll"

using System;
using System.Collections.Immutable;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;
using System.Reflection.PortableExecutable;

if (Args.Count == 0)
{
    Console.Error.WriteLine("Usage: asmprobe.csx <assembly-path> [type-name-filter]");
    return;
}

var assemblyPath = Path.GetFullPath(Args[0]);
var filter = Args.Count > 1 ? Args[1] : null;

using (var stream = File.OpenRead(assemblyPath))
using (var pe = new PEReader(stream))
{
    if (!pe.HasMetadata)
    {
        Console.Error.WriteLine("Target has no CLR metadata: " + assemblyPath);
        return;
    }

    var metadata = pe.GetMetadataReader();
    var provider = new DisplayTypeProvider(metadata);

    Console.WriteLine("Assembly: " + assemblyPath);
    foreach (var assemblyHandle in metadata.AssemblyReferences)
    {
        var reference = metadata.GetAssemblyReference(assemblyHandle);
        Console.WriteLine($"REF  {metadata.GetString(reference.Name)} {reference.Version}");
    }

    foreach (var handle in metadata.TypeDefinitions)
    {
        var type = metadata.GetTypeDefinition(handle);
        var ns = metadata.GetString(type.Namespace);
        var name = metadata.GetString(type.Name);
        var fullName = string.IsNullOrEmpty(ns) ? name : ns + "." + name;

        if (!string.IsNullOrEmpty(filter) &&
            fullName.IndexOf(filter, StringComparison.OrdinalIgnoreCase) < 0)
        {
            continue;
        }

        Console.WriteLine();
        Console.WriteLine("TYPE " + fullName);

        foreach (var fieldHandle in type.GetFields())
        {
            var field = metadata.GetFieldDefinition(fieldHandle);
            var fieldName = metadata.GetString(field.Name);
            var signature = field.DecodeSignature(provider, genericContext: null);
            Console.WriteLine("  FIELD  " + signature + " " + fieldName);
        }

        foreach (var methodHandle in type.GetMethods())
        {
            var method = metadata.GetMethodDefinition(methodHandle);
            var methodName = metadata.GetString(method.Name);
            var signature = method.DecodeSignature(provider, genericContext: null);
            var parameters = string.Join(", ", signature.ParameterTypes);
            Console.WriteLine(
                $"  METHOD {signature.ReturnType} {methodName}({parameters}) RVA=0x{method.RelativeVirtualAddress:X}");
        }
    }
}

sealed class DisplayTypeProvider : ISignatureTypeProvider<string, object>
{
    private readonly MetadataReader _metadata;

    public DisplayTypeProvider(MetadataReader metadata)
    {
        _metadata = metadata;
    }

    public string GetArrayType(string elementType, ArrayShape shape)
    {
        return elementType + "[]";
    }

    public string GetByReferenceType(string elementType)
    {
        return elementType + "&";
    }

    public string GetFunctionPointerType(MethodSignature<string> signature)
    {
        var parameters = string.Join(", ", signature.ParameterTypes);
        return $"fnptr<{signature.ReturnType} ({parameters})>";
    }

    public string GetGenericInstantiation(string genericType, ImmutableArray<string> typeArguments)
    {
        return genericType + "<" + string.Join(", ", typeArguments) + ">";
    }

    public string GetGenericMethodParameter(object genericContext, int index)
    {
        return "!!" + index;
    }

    public string GetGenericTypeParameter(object genericContext, int index)
    {
        return "!" + index;
    }

    public string GetModifiedType(string modifierType, string unmodifiedType, bool isRequired)
    {
        return unmodifiedType;
    }

    public string GetPinnedType(string elementType)
    {
        return elementType;
    }

    public string GetPointerType(string elementType)
    {
        return elementType + "*";
    }

    public string GetPrimitiveType(PrimitiveTypeCode typeCode)
    {
        switch (typeCode)
        {
            case PrimitiveTypeCode.Void: return "void";
            case PrimitiveTypeCode.Boolean: return "bool";
            case PrimitiveTypeCode.Char: return "char";
            case PrimitiveTypeCode.SByte: return "sbyte";
            case PrimitiveTypeCode.Byte: return "byte";
            case PrimitiveTypeCode.Int16: return "short";
            case PrimitiveTypeCode.UInt16: return "ushort";
            case PrimitiveTypeCode.Int32: return "int";
            case PrimitiveTypeCode.UInt32: return "uint";
            case PrimitiveTypeCode.Int64: return "long";
            case PrimitiveTypeCode.UInt64: return "ulong";
            case PrimitiveTypeCode.Single: return "float";
            case PrimitiveTypeCode.Double: return "double";
            case PrimitiveTypeCode.String: return "string";
            case PrimitiveTypeCode.Object: return "object";
            case PrimitiveTypeCode.IntPtr: return "nint";
            case PrimitiveTypeCode.UIntPtr: return "nuint";
            default: return typeCode.ToString();
        }
    }

    public string GetSZArrayType(string elementType)
    {
        return elementType + "[]";
    }

    public string GetTypeFromDefinition(MetadataReader reader, TypeDefinitionHandle handle, byte rawTypeKind)
    {
        var type = reader.GetTypeDefinition(handle);
        var ns = reader.GetString(type.Namespace);
        var name = reader.GetString(type.Name);
        return string.IsNullOrEmpty(ns) ? name : ns + "." + name;
    }

    public string GetTypeFromReference(MetadataReader reader, TypeReferenceHandle handle, byte rawTypeKind)
    {
        var type = reader.GetTypeReference(handle);
        var ns = reader.GetString(type.Namespace);
        var name = reader.GetString(type.Name);
        return string.IsNullOrEmpty(ns) ? name : ns + "." + name;
    }

    public string GetTypeFromSpecification(MetadataReader reader, object genericContext, TypeSpecificationHandle handle, byte rawTypeKind)
    {
        var type = reader.GetTypeSpecification(handle);
        return type.DecodeSignature(this, genericContext);
    }
}
